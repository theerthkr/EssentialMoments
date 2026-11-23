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

    @Test
    fun resolvesCaptureDateWhenPresent() {
        val summary = resolveImageDate(
            dateTakenMs = 1732320000000L,
            dateModifiedMs = 1732310000000L,
            dateAddedMs = 1732330000000L
        )
        assertEquals(1732320000000L, summary.primaryDateMs)
        assertEquals("Captured", summary.dateLabel)
        assertEquals(1732330000000L, summary.dateAddedMs)
    }

    @Test
    fun fallsBackToModifiedDateWhenCaptureTimeAbsent() {
        // Issue #37: An image without capture metadata has an older modified date
        // but was added to the emulator today.
        val modifiedMs = 1731900178000L // 18 Nov
        val addedMs = 1732322010000L    // 23 Nov
        val summary = resolveImageDate(
            dateTakenMs = 0L,
            dateModifiedMs = modifiedMs,
            dateAddedMs = addedMs
        )
        assertEquals(modifiedMs, summary.primaryDateMs)
        assertEquals("Modified", summary.dateLabel)
        assertEquals(addedMs, summary.dateAddedMs)
    }

    @Test
    fun fallsBackToAddedDateWhenCaptureAndModifiedAbsent() {
        val addedMs = 1732322010000L
        val summary = resolveImageDate(
            dateTakenMs = 0L,
            dateModifiedMs = 0L,
            dateAddedMs = addedMs
        )
        assertEquals(addedMs, summary.primaryDateMs)
        assertEquals("Added to library", summary.dateLabel)
        assertNull(summary.dateAddedMs)
    }

    @Test
    fun handlesMissingDatesGracefully() {
        val summary = resolveImageDate(
            dateTakenMs = 0L,
            dateModifiedMs = 0L,
            dateAddedMs = 0L
        )
        assertEquals(0L, summary.primaryDateMs)
        assertEquals("Date", summary.dateLabel)
        assertNull(summary.dateAddedMs)
    }
}
