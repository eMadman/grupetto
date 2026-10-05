package com.spop.poverlay.antplus

/** Android-free ANT+ page helpers shared by the channels, so they can be unit tested on the JVM. */
internal object AntPlusPages {
    const val CALIBRATION_PAGE = 0x01
    const val CALIBRATION_REQUEST_MANUAL_ZERO = 0xAA

    /**
     * Bike Power general calibration request (page 0x01, ID 0xAA).
     * `AcknowledgedDataMessage.payload` already excludes the channel number byte.
     */
    fun isCalibrationRequest(payload: ByteArray): Boolean =
        payload.size >= AntPlusConstants.ANT_MESSAGE_SIZE &&
            payload[0] == CALIBRATION_PAGE.toByte() &&
            payload[1] == CALIBRATION_REQUEST_MANUAL_ZERO.toByte()
}
