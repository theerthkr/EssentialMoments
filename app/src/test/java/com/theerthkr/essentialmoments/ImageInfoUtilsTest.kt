package com.theerthkr.essentialmoments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageInfoUtilsTest {

    @Test
    fun formatsLongExposureInSeconds() {
        assertEquals("2 s", formatExposureTime(2.0))
        assertEquals("2.5 s", formatExposureTime(2.5))
        assertEquals("30 s", formatExposureTime(30.0))
    }

    @Test
    fun formatsShortExposureAsFraction() {
        assertEquals("1/125 s", formatExposureTime(0.008))
        assertEquals("1/2 s", formatExposureTime(0.5))
        assertEquals("1/1000 s", formatExposureTime(0.001))
    }

    @Test
    fun rejectsNonPositiveExposure() {
        assertNull(formatExposureTime(0.0))
        assertNull(formatExposureTime(-1.0))
        assertNull(formatExposureTime(Double.NaN))
    }

    @Test
    fun parsesDecimalAndRationalExifValues() {
        assertEquals(2.0, parseExposureSeconds("2")!!, 0.0001)
        assertEquals(0.008, parseExposureSeconds("1/125")!!, 0.000001)
        assertEquals(0.5, parseExposureSeconds("0.5")!!, 0.000001)
        assertNull(parseExposureSeconds("1/0"))
        assertNull(parseExposureSeconds("nope"))
    }
}
