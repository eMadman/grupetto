package com.spop.poverlay.antplus

/**
 * ANT+ device profile IDs and message formats
 * Reference: https://www.thisisant.com/developer/ant-plus/device-profiles/
 */
object AntPlusConstants {
    // ANT+ Device Type IDs
    const val DEVICE_TYPE_POWER_METER = 11
    const val DEVICE_TYPE_SPEED_CADENCE = 121
    const val DEVICE_TYPE_HRM = 120

    // Common ANT+ settings
    const val ANT_RF_FREQ = 57 // 2457MHz
    // Some head units are stricter; keep a fallback tx type for discovery retries.
    val TRANSMISSION_TYPES_TO_TRY = intArrayOf(0x05, 0x01)
    const val DEVICE_NUMBER = 1

    // Channel Periods (Hz = 32768 / Period)
    const val POWER_METER_PERIOD = 8182 // ANT+ Bike Power profile period (~4.00Hz)
    const val SPEED_CADENCE_PERIOD = 8086 // ~4.052Hz
    const val HRM_PERIOD = 8070 // ANT+ HRM standard period (~4.06Hz)

    // Power Meter specific Pages
    const val POWER_METER_PAGE_STANDARD = 16
    const val PAGE_MANUFACTURER_INFO = 80
    const val PAGE_PRODUCT_INFO = 81
    const val COMMON_PAGE_ROTATION_INTERVAL = 61
    const val MANUFACTURER_PAGE_SLOT = 15
    const val PRODUCT_PAGE_SLOT = 30

    // Common-page metadata for ANT+ discovery/identification
    const val HARDWARE_REVISION = 1
    const val MANUFACTURER_ID = 12345 // Custom ID for Grupetto
    const val SOFTWARE_REVISION_MAIN = 1
    const val SOFTWARE_REVISION_SUPPLEMENTAL = 0

    // HRM specific
    const val HRM_PAGE_DATA = 0 // Main HRM data page (page 0)

    // ANT+ FE-C (Fitness Equipment Control) smart trainer
    const val DEVICE_TYPE_FITNESS_EQUIPMENT = 17
    const val FEC_DEVICE_NUMBER = 4
    const val FEC_PERIOD = 8192 // FE-C requires 4.00Hz
    const val FEC_PAGE_GENERAL = 16
    const val FEC_PAGE_TRAINER = 25
    const val FEC_PAGE_BASIC_RESISTANCE = 48
    const val FEC_PAGE_TARGET_POWER = 49
    const val FEC_PAGE_WIND_RESISTANCE = 50
    const val FEC_PAGE_TRACK_RESISTANCE = 51
    const val FEC_PAGE_CAPABILITIES = 54
    const val FEC_PAGE_USER_CONFIGURATION = 55
    const val FEC_PAGE_REQUEST = 70
    const val FEC_PAGE_COMMAND_STATUS = 71
    const val FEC_EQUIPMENT_TYPE_TRAINER = 25
    const val FEC_STATE_READY = 2
    const val FEC_STATE_IN_USE = 3
    const val FEC_CAPABILITY_FLAGS = 0x0C // distance traveled enabled, virtual speed
    const val FEC_CONTROL_CAPABILITIES = 0x07 // basic resistance, target power, simulation
    const val FEC_MAX_RESISTANCE_NEWTONS = 1000 // nominal; Peloton resistance has no force calibration
    const val FEC_COMMON_PAGE_INTERVAL = 64
    const val FEC_LEASE_IDLE_MS = 30_000L
    const val FEC_PRODUCT_INSTANCE = 0x04

    // ANT+ message sizes
    const val ANT_MESSAGE_SIZE = 8 // Standard ANT message payload is 8 bytes
}
