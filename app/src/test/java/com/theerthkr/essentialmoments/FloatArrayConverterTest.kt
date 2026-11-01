package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.data.FloatArrayConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatArrayConverterTest {

    private val converter = FloatArrayConverter()

    @Test
    fun roundTripPreservesValues() {
        val original = FloatArray(768) { it * 0.125f - 1.0f }

        val restored = converter.toFloatArray(converter.fromFloatArray(original))

        assertArrayEquals(original, restored, 0.0f)
    }

    @Test
    fun emptyArrayRoundTrips() {
        val restored = converter.toFloatArray(converter.fromFloatArray(FloatArray(0)))

        assertEquals(0, restored.size)
    }
}
