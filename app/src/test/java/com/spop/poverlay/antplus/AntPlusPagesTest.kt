package com.spop.poverlay.antplus

import org.junit.Assert.*
import org.junit.Test

class AntPlusPagesTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test fun `calibration request is matched on the page byte, not a channel byte`() {
        // AcknowledgedDataMessage.payload strips the channel number, so page 0x01 is byte 0.
        assertTrue(AntPlusPages.isCalibrationRequest(bytes(0x01, 0xAA, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertFalse(AntPlusPages.isCalibrationRequest(bytes(0x00, 0x01, 0xAA, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertFalse(AntPlusPages.isCalibrationRequest(bytes(0x01, 0xAB, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
        assertFalse(AntPlusPages.isCalibrationRequest(bytes(0x01, 0xAA)))
    }
}
