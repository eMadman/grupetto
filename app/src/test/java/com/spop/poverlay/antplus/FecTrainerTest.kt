package com.spop.poverlay.antplus

import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.control.Simulation
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class FecTrainerTest {
    private var now = 1000L
    private var resistance = 40
    private val control = BikeControl(true, { now }) { resistance = it; true }
    private val fec = FecTrainer(control) { now }

    private fun sample(power: Float = 100f, cadence: Float = 80f, elapsed: Long = 200) {
        now += elapsed
        control.acceptSample(BikeSample(power, cadence, resistance, now))
        control.tick()
    }
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun send(vararg values: Int) = fec.onAcknowledged(bytes(*values))
    private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF
    private fun u16(bytes: ByteArray, index: Int) = u8(bytes, index) or (u8(bytes, index + 1) shl 8)
    private fun request(page: Int, response: Int = 0x01) = send(70, 0xFF, 0xFF, 0xFF, 0xFF, response, page, 0x01)
    private fun nextOf(page: Int): ByteArray {
        repeat(80) { fec.nextPage().bytes.let { if (u8(it, 0) == page) return it } }
        fail("Page $page not sent"); error("unreachable")
    }
    private fun targetPower(watts: Int) = (watts * 4).let { send(49, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, it and 0xFF, it shr 8) }
    private fun track(gradePercent: Float, rollingRaw: Int = 0xFF) =
        Math.round((gradePercent + 200f) * 100f).let { send(51, 0xFF, 0xFF, 0xFF, 0xFF, it and 0xFF, it shr 8, rollingRaw) }

    @Test fun `target power page starts ERG and is clamped`() {
        sample()
        targetPower(215)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(215, control.state.value.targetWatts)
        assertEquals("ANT+", control.state.value.externalControl)
        targetPower(0)
        assertEquals(25, control.state.value.targetWatts)
        targetPower(2000)
        assertEquals(1000, control.state.value.targetWatts)
    }

    @Test fun `basic resistance page uses half percent units`() {
        sample()
        send(48, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 91)
        assertEquals(ControlMode.Resistance, control.state.value.mode)
        assertEquals(46, control.state.value.targetResistance)
        send(48, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 201)
        assertEquals(46, control.state.value.targetResistance)
        assertEquals(FecTrainer.StatusFail, u8(nextStatus(), 3))
    }

    // Sim starts from the current resistance (40) and moves at most 3 points/s; 20 s settles it.
    private fun settledResistance(): Int { repeat(100) { sample() }; return resistance }
    private fun expectedSim(wind: Float, grade: Float, rolling: Float, drag: Float) =
        (40f + Simulation(wind, grade, rolling, drag).resistanceOffset()).roundToInt().coerceIn(0, 100)

    @Test fun `track resistance uses FE-C defaults until a wind page arrives`() {
        sample()
        track(-3.5f)
        assertEquals(ControlMode.Simulation, control.state.value.mode)
        assertEquals(-3.5f, control.state.value.targetIncline, .001f)
        assertEquals(expectedSim(0f, -3.5f, .004f, .51f), settledResistance())
    }

    @Test fun `wind and drafting merge into simulation`() {
        sample()
        track(2f, rollingRaw = 200) // Crr 0.010
        assertEquals(expectedSim(0f, 2f, .010f, .51f), settledResistance())
        // Coefficient 0.60 kg/m, 10 km/h headwind (offset 127), drafting 0.50.
        send(50, 0xFF, 0xFF, 0xFF, 0xFF, 60, 137, 50)
        assertEquals(2f, control.state.value.targetIncline, .001f)
        assertEquals(expectedSim(10f / 3.6f, 2f, .010f, .30f), settledResistance())
        val status = nextStatus()
        assertEquals(50, u8(status, 1))
        assertEquals(FecTrainer.StatusPass, u8(status, 3))
        // A later grade keeps the cached wind values.
        track(0f, rollingRaw = 200)
        assertEquals(expectedSim(10f / 3.6f, 0f, .010f, .30f), settledResistance())
    }

    @Test fun `wind page alone does not start simulation`() {
        sample()
        send(50, 0xFF, 0xFF, 0xFF, 0xFF, 60, 137, 50)
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }

    @Test fun `capabilities request is answered the requested number of times then rotation resumes`() {
        request(54, response = 0x02)
        repeat(2) {
            val out = fec.nextPage()
            assertFalse(out.acknowledged)
            assertArrayEquals(bytes(54, 0xFF, 0xFF, 0xFF, 0xFF, 0xE8, 0x03, 0x07), out.bytes)
        }
        assertEquals(16, u8(fec.nextPage().bytes, 0))
        request(54, response = 0x81)
        assertTrue(fec.nextPage().acknowledged)
        assertFalse(fec.nextPage().acknowledged)
    }

    @Test fun `unsupported requests and command types are ignored`() {
        request(17)
        assertEquals(16, u8(fec.nextPage().bytes, 0))
        send(70, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 54, 0x02)
        assertEquals(25, u8(fec.nextPage().bytes, 0))
    }

    private fun nextStatus(): ByteArray {
        request(71)
        return fec.nextPage().bytes.also { assertEquals(71, u8(it, 0)) }
    }

    @Test fun `command status tracks sequence, result and data`() {
        val initial = nextStatus()
        assertArrayEquals(bytes(71, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), initial)
        targetPower(200) // stale: no fresh sample yet
        assertArrayEquals(bytes(71, 49, 0, FecTrainer.StatusFail, 0xFF, 0xFF, 0x20, 0x03), nextStatus())
        sample()
        targetPower(200)
        assertArrayEquals(bytes(71, 49, 1, FecTrainer.StatusPass, 0xFF, 0xFF, 0x20, 0x03), nextStatus())
    }

    @Test fun `commands are rejected while bluetooth owns control`() {
        sample()
        assertEquals(1, control.procedure("ble:a", byteArrayOf(0)).result)
        assertEquals(1, control.procedure("ble:a", byteArrayOf(5, 150.toByte(), 0)).result)
        targetPower(250)
        assertEquals(150, control.state.value.targetWatts)
        assertEquals(FecTrainer.StatusRejected, u8(nextStatus(), 3))
    }

    @Test fun `idle lease is released but the ERG target keeps running`() {
        sample()
        targetPower(220)
        now += AntPlusConstants.FEC_LEASE_IDLE_MS + 1
        fec.nextPage()
        sample()
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(220, control.state.value.targetWatts)
        assertEquals(1, control.procedure("ble:a", byteArrayOf(0)).result)
        targetPower(250)
        assertEquals(FecTrainer.StatusRejected, u8(nextStatus(), 3))
    }

    @Test fun `reset stops ANT control even after the lease was released`() {
        sample()
        targetPower(220)
        fec.reset()
        assertEquals(ControlMode.Manual, control.state.value.mode)
        targetPower(220)
        now += AntPlusConstants.FEC_LEASE_IDLE_MS + 1
        fec.nextPage()
        sample()
        assertEquals(ControlMode.Erg, control.state.value.mode)
        fec.reset()
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertArrayEquals(bytes(71, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), nextStatus())
    }

    @Test fun `reset leaves bluetooth control alone`() {
        sample()
        control.procedure("ble:a", byteArrayOf(0))
        control.procedure("ble:a", byteArrayOf(5, 150.toByte(), 0))
        fec.reset()
        assertEquals(ControlMode.Erg, control.state.value.mode)
    }

    @Test fun `general page encodes equipment type, speed, distance and state`() {
        fec.update(250, 0)
        fec.updateSpeed(36f) // 10 m/s
        val idle = fec.nextPage().bytes
        assertEquals(16, u8(idle, 0))
        assertEquals(25, u8(idle, 1))
        assertEquals(10_000, u16(idle, 4))
        assertEquals(0xFF, u8(idle, 6))
        assertEquals(0x2C, u8(idle, 7)) // READY + distance + virtual speed
        fec.update(250, 90)
        now += 1000
        fec.nextPage()
        now += 1000
        val riding = fec.nextPage().bytes
        assertEquals(16, u8(riding, 0))
        assertEquals(8, u8(riding, 2)) // 2 s in quarter seconds
        assertEquals(20, u8(riding, 3))
        assertEquals(0x3C, u8(riding, 7))
    }

    @Test fun `trainer page encodes 12 bit power, accumulated power and limit flags`() {
        sample(power = 100f)
        fec.update(3000, 85)
        fec.nextPage()
        val first = fec.nextPage().bytes
        assertEquals(25, u8(first, 0))
        assertEquals(1, u8(first, 1))
        assertEquals(85, u8(first, 2))
        assertEquals(3000, u16(first, 3))
        assertEquals(3000, u8(first, 5) or ((u8(first, 6) and 0x0F) shl 8))
        assertEquals(0, u8(first, 6) shr 4)
        assertEquals(0x30, u8(first, 7))
        repeat(21) { fec.update(3000, 85); fec.nextPage(); fec.nextPage() }
        val wrapped = nextOf(25)
        assertEquals((23 * 3000) and 0xFFFF, u16(wrapped, 3))

        fec.update(5000, 85)
        assertEquals(0xFFE, nextOf(25).let { u8(it, 5) or ((u8(it, 6) and 0x0F) shl 8) })

        targetPower(300)
        sample(power = 100f, cadence = 10f)
        assertEquals(1, u8(nextOf(25), 7) and 0x0F)
    }

    @Test fun `common pages appear every 64 messages with a distinct serial`() {
        val pages = List(68) { fec.nextPage().bytes }
        assertEquals(List(32) { listOf(16, 25) }.flatten() + listOf(80, 80, 81, 81), pages.map { u8(it, 0) })
        assertEquals(AntPlusConstants.FEC_PRODUCT_INSTANCE, u8(pages.last(), 7))
        assertEquals(16, u8(fec.nextPage().bytes, 0))
    }

    @Test fun `user configuration and last command pages are echoed on request`() {
        sample()
        send(55, 0x10, 0x1B, 0xFF, 0x0A, 0x46, 0x46, 0x34)
        request(55)
        assertArrayEquals(bytes(55, 0x10, 0x1B, 0xFF, 0x0A, 0x46, 0x46, 0x34), fec.nextPage().bytes)
        targetPower(200)
        request(49)
        assertArrayEquals(bytes(49, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x20, 0x03), fec.nextPage().bytes)
    }
}
