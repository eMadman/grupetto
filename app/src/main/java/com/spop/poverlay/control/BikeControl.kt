package com.spop.poverlay.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only the mainboard platform identifies a Bike+; Topaz tablets are shared with Row/Tread. */
fun supportsBikeControl(isPeloton: Boolean, platform: String?, model: String = ""): Boolean {
    if (!isPeloton) return false
    val machine = platform?.trim()?.lowercase()
    if (machine == "caesar" || machine == "aurora" || machine == "v1" ||
        com.spop.poverlay.util.isTreadPlatform(machine)) return false
    return machine == "titan" || com.spop.poverlay.util.isG700CrossTrainerModel(model)
}

data class BikeSample(val power: Float, val cadence: Float, val resistance: Int, val timestamp: Long)
enum class ControlMode { Manual, Erg, Simulation, Resistance }
data class ControlState(
    val connected: Boolean = false,
    val mode: ControlMode = ControlMode.Manual,
    val targetWatts: Int = 150,
    val targetResistance: Int = 0,
    val targetIncline: Float = 0f,
    val wattsPerShift: Int = 10,
    val inclineSensitivity: Float = 2f,
    val externalControl: String? = null,
    val shiftOffset: Int = 0,
    val shiftSize: Int = 2,
    val gain: Float = .007f,
    val permissionLostCount: Int = 0,
    val message: String = "Waiting for Bike+"
)
data class Simulation(val wind: Float, val grade: Float, val rolling: Float, val drag: Float) {
    // A repeatable resistance model: 2 Peloton points per percent grade, with wind and
    // rolling resistance expressed as equivalent grade at 30 km/h and 85 kg total mass.
    // This is a trainer feel model, not a claim of calibrated road power or rider mass.
    fun resistanceOffset(sensitivity: Float = 2f): Float {
        val airSpeed = 30f / 3.6f + wind
        val extraForce = drag * airSpeed * abs(airSpeed) - .51f * (30f / 3.6f) * (30f / 3.6f)
        return sensitivity * (grade + (rolling - .004f) * 100f + extraForce / (85f * 9.80665f) * 100f)
    }
}

/** Serialized, clock-injected control core. No Android stubs or sleeping threads in tests.
 * Motor transaction and proportional ERG approach inspired by dwj300's PR #50.
 */
class BikeControl(
    val supported: Boolean,
    private val clock: () -> Long,
    private val writeResistance: (Int) -> Boolean
) {
    private val mutableState = MutableStateFlow(ControlState())
    val state = mutableState.asStateFlow()
    private var sample: BikeSample? = null
    private var owner: String? = null
    private var filteredPower: Float? = null
    private var requestedResistance = 0f
    private var expectedResistance: Int? = null
    private var commandTime = 0L
    private var previousTick = 0L
    private var simBase = 0f
    private var simulation = Simulation(0f, 0f, .004f, .51f)
    private var pausedMode: ControlMode? = null
    private var pausedShift = 0
    private var pausedResistance = 0
    private var externalPower: com.spop.poverlay.sensor.power.ExternalPowerReading? = null
    private var externalClient: String? = null

    @Synchronized fun useExternalPower(reading: com.spop.poverlay.sensor.power.ExternalPowerReading?) {
        val next = reading?.takeIf { com.spop.poverlay.sensor.power.freshExternalPower(it, clock()) != null }
        if (externalPower?.address != next?.address) {
            if (state.value.mode == ControlMode.Erg || pausedMode == ControlMode.Erg)
                stop("Power source changed · restart ERG")
            filteredPower = null
        }
        externalPower = next
    }

    @Synchronized fun tune(shiftSize: Int, gain: Float,
        wattsPerShift: Int = state.value.wattsPerShift,
        inclineSensitivity: Float = state.value.inclineSensitivity) {
        mutableState.value = state.value.copy(shiftSize = shiftSize.coerceIn(1, 10),
            wattsPerShift = wattsPerShift.coerceIn(1, 50),
            inclineSensitivity = if (inclineSensitivity.isFinite()) inclineSensitivity.coerceIn(0f, 5f) else 2f,
            gain = if (gain.isFinite()) gain.coerceIn(.001f, .03f) else .007f)
    }

    @Synchronized fun acceptSample(value: BikeSample?) {
        if (!supported) return
        if (value == null || !value.power.isFinite() || value.power < 0 ||
            !value.cadence.isFinite() || value.cadence < 0 || value.resistance !in 0..100) {
            sample = null
            unavailable("Bike+ disconnected")
            return
        }
        sample = value
        if (clock() - value.timestamp !in 0..1500) { unavailable("Bike+ data is stale"); return }
        mutableState.value = state.value.copy(connected = true,
            targetResistance = if (state.value.mode == ControlMode.Manual) value.resistance else state.value.targetResistance,
            message = if (!state.value.connected) "Ready" else state.value.message)
    }

    private fun fresh(): Boolean = supported && sample?.let { clock() - it.timestamp in 0..1500 } == true
    private fun unavailable(reason: String) {
        stop(reason)
        mutableState.value = state.value.copy(connected = false)
    }
    @Synchronized fun stop(reason: String = "Manual control", releaseControl: Boolean = true) {
        val lostPermission = releaseControl && owner != null
        if (releaseControl) owner = null
        pausedMode = null
        expectedResistance = null
        filteredPower = null
        mutableState.value = state.value.copy(mode = ControlMode.Manual, shiftOffset = 0, message = reason, externalControl = null,
            targetResistance = sample?.resistance ?: state.value.targetResistance,
            permissionLostCount = state.value.permissionLostCount + if (lostPermission) 1 else 0)
    }
    @Synchronized fun disconnect(client: String) { if (owner == client || releasedLeaseFrom { it == client }) stop("Controller disconnected") }
    @Synchronized fun disconnectTransport(prefix: String) {
        if (owner?.startsWith(prefix) == true || releasedLeaseFrom { it.startsWith(prefix) }) stop("Controller disconnected")
    }
    // A released idle lease leaves its target running; losing that transport must still stop it.
    private fun releasedLeaseFrom(match: (String) -> Boolean) =
        owner == null && state.value.externalControl != null && externalClient?.let(match) == true

    private fun setMode(mode: ControlMode) {
        pausedMode = null
        if (state.value.mode != mode) {
            requestedResistance = sample!!.resistance.toFloat()
            expectedResistance = sample!!.resistance
            commandTime = clock()
            previousTick = clock()
            filteredPower = null
            simBase = requestedResistance
            mutableState.value = state.value.copy(mode = mode, shiftOffset = 0)
        }
        mutableState.value = state.value.copy(message = "${mode.name} active")
    }
    @Synchronized fun localErg(watts: Int): Boolean {
        if (!fresh() || watts !in 25..1000) return false
        setMode(ControlMode.Erg)
        mutableState.value = state.value.copy(targetWatts = watts, externalControl = null)
        return true
    }
    @Synchronized fun localSimulation(grade: Float = state.value.targetIncline): Boolean {
        if (!fresh() || !grade.isFinite() || grade !in -327.68f..327.67f) return false
        simulation = Simulation(0f, grade, .004f, .51f)
        setMode(ControlMode.Simulation)
        mutableState.value = state.value.copy(targetIncline = grade, externalControl = null)
        return true
    }
    // Local adjustments do not acquire or revoke the remote FTMS lease. The next
    // command from its owner wins, including while the settings dialog is open.
    @Synchronized fun localManual(): Boolean {
        if (!fresh()) return false
        return localResistance(sample!!.resistance)
    }
    @Synchronized fun localResistance(level: Int): Boolean {
        if (!fresh() || level !in 0..100) return false
        setMode(ControlMode.Resistance)
        mutableState.value = state.value.copy(targetResistance = level, message = "Manual active", externalControl = null)
        return true
    }
    @Synchronized fun shift(direction: Int) {
        if (!fresh()) return
        val step = direction.coerceIn(-1, 1)
        if (step == 0) return
        when (state.value.mode) {
            ControlMode.Erg -> { localErg((state.value.targetWatts + step * state.value.wattsPerShift).coerceIn(25, 1000)); return }
            ControlMode.Manual, ControlMode.Resistance -> {
                val base = if (state.value.mode == ControlMode.Manual) sample!!.resistance else state.value.targetResistance
                localResistance((base + step * state.value.shiftSize).coerceIn(0, 100)); return
            }
            ControlMode.Simulation -> Unit
        }
        val base = simBase + simulation.resistanceOffset(state.value.inclineSensitivity)
        val current = (base + state.value.shiftOffset).coerceIn(0f, 100f)
        val next = (current + direction.coerceIn(-1, 1) * state.value.shiftSize).coerceIn(0f, 100f)
        mutableState.value = state.value.copy(shiftOffset = (next - base).roundToInt(), externalControl = null)
    }

    /** Called at 100 ms; only new telemetry drives feedback. Stale data disarms control. */
    private var lastSampleTime = Long.MIN_VALUE
    @Synchronized fun tick() {
        if (externalPower != null && com.spop.poverlay.sensor.power.freshExternalPower(externalPower, clock()) == null)
            useExternalPower(null)
        if (!fresh()) { if (state.value.connected) unavailable("Bike+ data is stale"); return }
        val reading = sample ?: return
        if (state.value.mode == ControlMode.Manual || reading.timestamp == lastSampleTime) return
        lastSampleTime = reading.timestamp
        val dt = ((clock() - previousTick) / 1000f).coerceIn(0f, .4f)
        previousTick = clock()
        val expected = expectedResistance
        if (expected != null && reading.resistance != expected) {
            // Allow the binder target echo to catch up. Do not keep moving the motor
            // against a knob override or a mainboard that ignored a write.
            if (clock() - commandTime > 1500) stop("Resistance changed manually or command not acknowledged")
            return
        }
        if (reading.cadence < 25f) {
            filteredPower = null
            requestedResistance = reading.resistance.toFloat()
            return
        }
        val target = when (state.value.mode) {
            ControlMode.Erg -> {
                val measured = com.spop.poverlay.sensor.power.freshExternalPower(externalPower, clock()) ?: reading.power
                val power = filteredPower?.let { it + dt / (2f + dt) * (measured - it) } ?: measured
                filteredPower = power
                val error = state.value.targetWatts - power
                requestedResistance + if (abs(error) <= 3f) 0f else state.value.gain * error * dt / .1f
            }
            ControlMode.Simulation -> simBase + simulation.resistanceOffset(state.value.inclineSensitivity) + state.value.shiftOffset
            ControlMode.Resistance -> state.value.targetResistance.toFloat()
            else -> return
        }
        // Limit every mode to 3 Peloton resistance points / second, including mode changes.
        requestedResistance = target.coerceIn(requestedResistance - 3f * dt, requestedResistance + 3f * dt).coerceIn(0f, 100f)
        val next = requestedResistance.roundToInt()
        if (next != reading.resistance) {
            if (!writeResistance(next)) { stop("Resistance command failed"); return }
            expectedResistance = next
            commandTime = clock()
        }
    }

    /** FTMS v1.0.1 control point. Returns the protocol result and optional Machine Status. */
    data class Reply(val result: Int, val status: ByteArray? = null)
    @Synchronized fun procedure(client: String, bytes: ByteArray): Reply {
        val op = bytes.firstOrNull()?.toInt()?.and(255) ?: return Reply(3)
        val size = when (op) { 0, 1, 7 -> 1; 8 -> 2; 4, 5 -> 3; 0x11 -> 7; else -> return Reply(2) }
        if (bytes.size != size) return Reply(3)
        if (!supported) return Reply(2)
        if (!fresh()) return Reply(4)
        if (op == 0) {
            if (owner != null && owner != client) return Reply(5)
            owner = client
            return Reply(1)
        }
        if (owner != client) return Reply(5)
        fun signed(index: Int) = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
        val reply = when (op) {
            1 -> { stop("Reset", releaseControl = false); Reply(1, byteArrayOf(1)) }
            7 -> {
                pausedMode?.let { mode ->
                    requestedResistance = sample!!.resistance.toFloat()
                    expectedResistance = sample!!.resistance
                    previousTick = clock()
                    commandTime = clock()
                    mutableState.value = state.value.copy(mode = mode, shiftOffset = pausedShift,
                        targetResistance = pausedResistance, message = "${mode.name} active")
                }
                pausedMode = null
                Reply(1, byteArrayOf(4))
            }
            8 -> {
                if (bytes[1].toInt() !in 1..2) Reply(3)
                else {
                    val mode = state.value.mode
                    val shift = state.value.shiftOffset
                    val resistance = state.value.targetResistance
                    stop(if (bytes[1].toInt() == 2) "Paused" else "Stopped", releaseControl = false)
                    if (bytes[1].toInt() == 2) { pausedMode = mode; pausedShift = shift; pausedResistance = resistance }
                    Reply(1, byteArrayOf(2, bytes[1]))
                }
            }
            4 -> {
                // Corrected FTMS format: SINT16 in tenths (FTMS TS p6 BV-25-C).
                val level = signed(1)
                if (level !in 0..1000 || level % 10 != 0) Reply(3)
                else Reply(command(client, RemoteCommand.Resistance(level / 10))).withStatus(byteArrayOf(7) + bytes.copyOfRange(1, 3))
            }
            5 -> Reply(command(client, RemoteCommand.Erg(signed(1)))).withStatus(byteArrayOf(8) + bytes.copyOfRange(1, 3))
            else -> Reply(command(client, RemoteCommand.Sim(Simulation(signed(1) * .001f, signed(3) * .01f,
                (bytes[5].toInt() and 255) * .0001f, (bytes[6].toInt() and 255) * .01f))))
                .withStatus(byteArrayOf(0x12) + bytes.copyOfRange(1, 7))
        }
        if (reply.result == 1 && op == 7 && state.value.mode != ControlMode.Manual) markExternal(client)
        return reply
    }
    private fun Reply.withStatus(status: ByteArray) = if (result == 1) copy(status = status) else this

    /** Transport-neutral remote targets shared by FTMS, DirCon and ANT+ FE-C. */
    sealed interface RemoteCommand {
        data class Resistance(val level: Int) : RemoteCommand
        data class Erg(val watts: Int) : RemoteCommand
        data class Sim(val simulation: Simulation) : RemoteCommand
    }

    /**
     * Applies a remote target and returns an FTMS result code: 1 success, 2 unsupported,
     * 3 invalid, 4 failed (stale data), 5 another client owns control. FE-C has no
     * Request Control procedure, so ANT+ passes [implicitAcquire] to take a free lease.
     */
    @Synchronized fun command(client: String, command: RemoteCommand, implicitAcquire: Boolean = false): Int {
        if (!supported) return 2
        if (!fresh()) return 4
        if (implicitAcquire && owner == null) owner = client
        if (owner != client) return 5
        when (command) {
            is RemoteCommand.Resistance -> {
                if (command.level !in 0..100) return 3
                setMode(ControlMode.Resistance)
                mutableState.value = state.value.copy(targetResistance = command.level, message = "Manual active")
            }
            is RemoteCommand.Erg -> {
                if (command.watts !in 25..1000) return 3
                setMode(ControlMode.Erg)
                mutableState.value = state.value.copy(targetWatts = command.watts)
            }
            is RemoteCommand.Sim -> {
                val sim = command.simulation
                if (!sim.wind.isFinite() || !sim.grade.isFinite() || !sim.rolling.isFinite() || !sim.drag.isFinite()) return 3
                simulation = sim
                setMode(ControlMode.Simulation)
                mutableState.value = state.value.copy(targetIncline = sim.grade)
            }
        }
        markExternal(client)
        return 1
    }

    /** Frees an idle remote lease while keeping the active mode and target running. */
    @Synchronized fun releaseOwner(client: String) { if (owner == client) owner = null }

    /** FE-C target power limit: 0 at target or not in ERG, 1 speed too low, 2 speed too high. */
    @Synchronized fun targetLimit(): Int {
        if (state.value.mode != ControlMode.Erg) return 0
        val reading = sample ?: return 0
        if (reading.cadence < 25f) return 1
        val error = state.value.targetWatts - (filteredPower ?: return 0)
        return when {
            requestedResistance >= 100f && error > 3f -> 1
            requestedResistance <= 0f && error < -3f -> 2
            else -> 0
        }
    }

    private fun markExternal(client: String) {
        externalClient = client
        mutableState.value = state.value.copy(externalControl = when {
            client.startsWith("dircon:") -> "DirCon"
            client.startsWith("ant:") -> "ANT+"
            else -> "Bluetooth"
        })
    }
}
