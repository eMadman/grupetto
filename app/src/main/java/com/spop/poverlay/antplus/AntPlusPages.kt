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

    /** Common page 80. Fully overwrites [buffer]. */
    fun writeManufacturerInfo(buffer: ByteArray): ByteArray {
        buffer[0] = AntPlusConstants.PAGE_MANUFACTURER_INFO.toByte()
        buffer[1] = 0xFF.toByte()
        buffer[2] = 0xFF.toByte()
        buffer[3] = AntPlusConstants.HARDWARE_REVISION.toByte()
        buffer[4] = (AntPlusConstants.MANUFACTURER_ID and 0xFF).toByte()
        buffer[5] = ((AntPlusConstants.MANUFACTURER_ID shr 8) and 0xFF).toByte()
        // Model bytes: "GR" (Grupetto)
        buffer[6] = 0x47.toByte()
        buffer[7] = 0x52.toByte()
        return buffer
    }

    /** Common page 81. [instance] keeps each channel's serial number distinct. Fully overwrites [buffer]. */
    fun writeProductInfo(buffer: ByteArray, instance: Int = 0x01): ByteArray {
        buffer[0] = AntPlusConstants.PAGE_PRODUCT_INFO.toByte()
        buffer[1] = 0xFF.toByte()
        buffer[2] = AntPlusConstants.SOFTWARE_REVISION_SUPPLEMENTAL.toByte()
        buffer[3] = AntPlusConstants.SOFTWARE_REVISION_MAIN.toByte()
        // Serial number bytes: "EP" + version + instance
        buffer[4] = 0x45.toByte()
        buffer[5] = 0x50.toByte()
        buffer[6] = 0x00.toByte()
        buffer[7] = instance.toByte()
        return buffer
    }
}
