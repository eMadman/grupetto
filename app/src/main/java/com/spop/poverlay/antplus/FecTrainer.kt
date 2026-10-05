package com.spop.poverlay.antplus

import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.control.Simulation
import kotlin.math.roundToInt

/**
 * ANT+ FE-C (Fitness Equipment Control) trainer profile, kept free of Android and radio
 * types so it can be unit tested. The handler calls [nextPage] on every TX event and passes
 * acknowledged pages from the display (e.g. a Garmin head unit) to [onAcknowledged].
 *
 * Commands share [BikeControl] with FTMS/DirCon: first owner wins, and FE-C takes a free
 * lease implicitly because the profile has no Request Control procedure.
 */
class FecTrainer(private val control: BikeControl, private val clock: () -> Long) {
    companion object {
        const val Client = "ant:fec"
        private const val DefaultDragCoefficient = .51f
        private const val DefaultRollingResistance = .004f

        // FE-C Command Status values
        const val StatusPass = 0
        const val StatusFail = 1
        const val StatusNotSupported = 2
        const val StatusRejected = 3
        const val StatusUninitialized = 0xFF

        private val AnswerablePages = setOf(
            AntPlusConstants.FEC_PAGE_BASIC_RESISTANCE, AntPlusConstants.FEC_PAGE_TARGET_POWER,
            AntPlusConstants.FEC_PAGE_WIND_RESISTANCE, AntPlusConstants.FEC_PAGE_TRACK_RESISTANCE,
            AntPlusConstants.FEC_PAGE_CAPABILITIES, AntPlusConstants.FEC_PAGE_USER_CONFIGURATION,
            AntPlusConstants.FEC_PAGE_COMMAND_STATUS, AntPlusConstants.PAGE_MANUFACTURER_INFO,
            AntPlusConstants.PAGE_PRODUCT_INFO
        )
    }

    data class Outgoing(val bytes: ByteArray, val acknowledged: Boolean)
    private class Pending(val page: Int, var remaining: Int, val acknowledged: Boolean)

    private var powerWatts = 0
    private var cadenceRpm = 0
    private var speedKmh = 0f

    private var messageCount = 0
    private var trainerEventCount = 0
    private var accumulatedPower = 0
    private var elapsedMs = 0L
    private var distanceMeters = 0.0
    private var lastPageAt: Long? = null

    private val pending = ArrayDeque<Pending>()
    private val lastCommandPages = mutableMapOf<Int, ByteArray>()
    private var userConfiguration: ByteArray? = null
    private var lastCommandPage = 0xFF
    private var sequence = 0xFF
    private var commandStatus = StatusUninitialized
    private var commandData = byteArrayOf(-1, -1, -1, -1)
    private var lastCommandAt: Long? = null

    // Page 50/51 values are sent separately but combine into one Sim target.
    private var windMps = 0f
    private var drag = DefaultDragCoefficient
    private var grade = 0f
    private var rolling = DefaultRollingResistance

    @Synchronized fun update(powerWatts: Int, cadenceRpm: Int) {
        this.powerWatts = powerWatts.coerceAtLeast(0)
        this.cadenceRpm = cadenceRpm.coerceAtLeast(0)
    }

    @Synchronized fun updateSpeed(speedKmh: Float) {
        this.speedKmh = if (speedKmh.isFinite()) speedKmh.coerceAtLeast(0f) else 0f
    }

    /** Handles a page from the display. [payload] excludes the ANT channel number byte. */
    @Synchronized fun onAcknowledged(payload: ByteArray) {
        if (payload.size < AntPlusConstants.ANT_MESSAGE_SIZE) return
        when (val page = payload[0].toInt() and 0xFF) {
            AntPlusConstants.FEC_PAGE_BASIC_RESISTANCE -> {
                val halfPercent = payload[7].toInt() and 0xFF
                if (halfPercent > 200) recordCommand(payload, 3)
                else recordCommand(payload, control.command(Client,
                    BikeControl.RemoteCommand.Resistance((halfPercent * .5f).roundToInt()), implicitAcquire = true))
            }
            AntPlusConstants.FEC_PAGE_TARGET_POWER -> {
                val quarterWatts = u16(payload, 6)
                recordCommand(payload, control.command(Client,
                    BikeControl.RemoteCommand.Erg((quarterWatts / 4).coerceIn(25, 1000)), implicitAcquire = true))
            }
            AntPlusConstants.FEC_PAGE_WIND_RESISTANCE -> {
                val coefficient = payload[5].toInt() and 0xFF
                val wind = payload[6].toInt() and 0xFF
                val drafting = payload[7].toInt() and 0xFF
                drag = (if (coefficient == 0xFF) DefaultDragCoefficient else coefficient * .01f) *
                    (if (drafting == 0xFF) 1f else (drafting * .01f).coerceAtMost(1f))
                // Wind speed is km/h with a -127 offset; positive is a headwind, as in FTMS.
                windMps = if (wind == 0xFF) 0f else (wind - 127) / 3.6f
                // Only re-apply Sim if it is already active; wind alone is not a target.
                recordCommand(payload, if (control.state.value.mode == ControlMode.Simulation)
                    control.command(Client, simulation(), implicitAcquire = true) else 1)
            }
            AntPlusConstants.FEC_PAGE_TRACK_RESISTANCE -> {
                val rawGrade = u16(payload, 5)
                grade = if (rawGrade == 0xFFFF) 0f else rawGrade * .01f - 200f
                val rawRolling = payload[7].toInt() and 0xFF
                rolling = if (rawRolling == 0xFF) DefaultRollingResistance else rawRolling * .00005f
                recordCommand(payload, control.command(Client, simulation(), implicitAcquire = true))
            }
            AntPlusConstants.FEC_PAGE_USER_CONFIGURATION -> userConfiguration = payload.copyOf(AntPlusConstants.ANT_MESSAGE_SIZE)
            AntPlusConstants.FEC_PAGE_REQUEST -> {
                val requested = payload[6].toInt() and 0xFF
                if ((payload[7].toInt() and 0xFF) != 0x01 || requested !in AnswerablePages) return
                val response = payload[5].toInt() and 0xFF
                pending.addLast(Pending(requested, (response and 0x7F).coerceAtLeast(1), response and 0x80 != 0))
            }
            else -> Unit // Unsupported pages are ignored, as the profile allows.
        }
    }

    private fun simulation() = BikeControl.RemoteCommand.Sim(Simulation(windMps, grade, rolling, drag))

    private fun recordCommand(payload: ByteArray, ftmsResult: Int) {
        val page = payload[0].toInt() and 0xFF
        lastCommandPages[page] = payload.copyOf(AntPlusConstants.ANT_MESSAGE_SIZE)
        lastCommandPage = page
        sequence = (sequence + 1) and 0xFF
        commandStatus = when (ftmsResult) {
            1 -> StatusPass
            2 -> StatusNotSupported
            5 -> StatusRejected
            else -> StatusFail
        }
        commandData = payload.copyOfRange(4, 8)
        if (ftmsResult == 1) lastCommandAt = clock()
    }

    /** Returns the next page for this TX slot: requested replies first, then the base rotation. */
    @Synchronized fun nextPage(): Outgoing {
        val now = clock()
        lastPageAt?.let { previous ->
            val dt = (now - previous).coerceIn(0, 2000)
            if (cadenceRpm > 0) elapsedMs += dt
            distanceMeters += speedKmh / 3.6 * dt / 1000.0
        }
        lastPageAt = now
        lastCommandAt?.let {
            if (now - it > AntPlusConstants.FEC_LEASE_IDLE_MS) {
                control.releaseOwner(Client)
                lastCommandAt = null
            }
        }

        pending.firstOrNull()?.let { reply ->
            if (--reply.remaining <= 0) pending.removeFirst()
            return Outgoing(page(reply.page), reply.acknowledged)
        }

        val cycle = AntPlusConstants.FEC_COMMON_PAGE_INTERVAL + 4
        val slot = messageCount % cycle
        messageCount = (messageCount + 1) % cycle
        val page = when (slot) {
            AntPlusConstants.FEC_COMMON_PAGE_INTERVAL, AntPlusConstants.FEC_COMMON_PAGE_INTERVAL + 1 ->
                AntPlusConstants.PAGE_MANUFACTURER_INFO
            AntPlusConstants.FEC_COMMON_PAGE_INTERVAL + 2, AntPlusConstants.FEC_COMMON_PAGE_INTERVAL + 3 ->
                AntPlusConstants.PAGE_PRODUCT_INFO
            else -> if (slot % 2 == 0) AntPlusConstants.FEC_PAGE_GENERAL else AntPlusConstants.FEC_PAGE_TRAINER
        }
        return Outgoing(page(page), false)
    }

    private fun page(number: Int): ByteArray {
        val bytes = ByteArray(AntPlusConstants.ANT_MESSAGE_SIZE) { 0xFF.toByte() }
        bytes[0] = number.toByte()
        val state = (if (cadenceRpm > 0) AntPlusConstants.FEC_STATE_IN_USE else AntPlusConstants.FEC_STATE_READY) shl 4
        when (number) {
            AntPlusConstants.FEC_PAGE_GENERAL -> {
                bytes[1] = AntPlusConstants.FEC_EQUIPMENT_TYPE_TRAINER.toByte()
                bytes[2] = (elapsedMs / 250).toInt().toByte()
                bytes[3] = distanceMeters.toLong().toInt().toByte()
                putU16(bytes, 4, (speedKmh / 3.6f * 1000f).roundToInt().coerceIn(0, 0xFFFE))
                // Heart rate stays 0xFF: the HRM channel already broadcasts it.
                bytes[7] = (AntPlusConstants.FEC_CAPABILITY_FLAGS or state).toByte()
            }
            AntPlusConstants.FEC_PAGE_TRAINER -> {
                val power = powerWatts.coerceIn(0, 0xFFE)
                trainerEventCount = (trainerEventCount + 1) and 0xFF
                accumulatedPower = (accumulatedPower + power) and 0xFFFF
                bytes[1] = trainerEventCount.toByte()
                bytes[2] = cadenceRpm.coerceIn(0, 254).toByte()
                putU16(bytes, 3, accumulatedPower)
                bytes[5] = (power and 0xFF).toByte()
                // High nibble: trainer status bits; no calibration or user configuration required.
                bytes[6] = ((power shr 8) and 0x0F).toByte()
                bytes[7] = ((control.targetLimit() and 0x0F) or state).toByte()
            }
            AntPlusConstants.FEC_PAGE_CAPABILITIES -> {
                putU16(bytes, 5, AntPlusConstants.FEC_MAX_RESISTANCE_NEWTONS)
                bytes[7] = AntPlusConstants.FEC_CONTROL_CAPABILITIES.toByte()
            }
            AntPlusConstants.FEC_PAGE_USER_CONFIGURATION ->
                userConfiguration?.copyInto(bytes) ?: run { bytes[7] = 0 } // gear ratio 0 = invalid
            AntPlusConstants.FEC_PAGE_COMMAND_STATUS -> {
                bytes[1] = lastCommandPage.toByte()
                bytes[2] = sequence.toByte()
                bytes[3] = commandStatus.toByte()
                commandData.copyInto(bytes, 4)
            }
            AntPlusConstants.FEC_PAGE_BASIC_RESISTANCE, AntPlusConstants.FEC_PAGE_TARGET_POWER,
            AntPlusConstants.FEC_PAGE_WIND_RESISTANCE, AntPlusConstants.FEC_PAGE_TRACK_RESISTANCE ->
                lastCommandPages[number]?.copyInto(bytes)
            AntPlusConstants.PAGE_MANUFACTURER_INFO -> AntPlusPages.writeManufacturerInfo(bytes)
            AntPlusConstants.PAGE_PRODUCT_INFO -> AntPlusPages.writeProductInfo(bytes, AntPlusConstants.FEC_PRODUCT_INSTANCE)
        }
        return bytes
    }

    /** Clears profile state and releases any control held over ANT+. */
    @Synchronized fun reset() {
        messageCount = 0
        trainerEventCount = 0
        accumulatedPower = 0
        elapsedMs = 0
        distanceMeters = 0.0
        lastPageAt = null
        pending.clear()
        lastCommandPages.clear()
        userConfiguration = null
        lastCommandPage = 0xFF
        sequence = 0xFF
        commandStatus = StatusUninitialized
        commandData = byteArrayOf(-1, -1, -1, -1)
        lastCommandAt = null
        windMps = 0f
        drag = DefaultDragCoefficient
        grade = 0f
        rolling = DefaultRollingResistance
        control.disconnectTransport("ant:")
    }

    private fun u16(bytes: ByteArray, index: Int) =
        (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

    private fun putU16(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = (value and 0xFF).toByte()
        bytes[index + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
