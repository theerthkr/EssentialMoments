package com.theerthkr.essentialmoments.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhotoFingerprintTest {

    @Test
    fun sameMetadataProducesSameFingerprint() {
        assertEquals(
            photoFingerprint(100L, 200L, 300, 400, 500L),
            photoFingerprint(100L, 200L, 300, 400, 500L)
        )
    }

    @Test
    fun changedModificationTimeChangesFingerprint() {
        assertNotEquals(
            photoFingerprint(100L, 200L, 300, 400, 500L),
            photoFingerprint(101L, 200L, 300, 400, 500L)
        )
    }

    @Test
    fun changedContentMetadataChangesFingerprint() {
        val original = photoFingerprint(100L, 200L, 300, 400, 500L)
        assertNotEquals(original, photoFingerprint(100L, 201L, 300, 400, 500L))
        assertNotEquals(original, photoFingerprint(100L, 200L, 301, 400, 500L))
        assertNotEquals(original, photoFingerprint(100L, 200L, 300, 401, 500L))
        assertNotEquals(original, photoFingerprint(100L, 200L, 300, 400, 501L))
    }
}
