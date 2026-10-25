package com.theerthkr.essentialmoments

import com.theerthkr.essentialmoments.ml.EmbeddingModelFactory
import com.theerthkr.essentialmoments.ml.SiglipImagePreprocessor
import com.theerthkr.essentialmoments.ml.litert.LiteRtSiglipImageEmbedder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class SiglipImageProcessorTest {

    @Test
    fun `argbToNormalizedNchw converts packed ARGB pixels into channel-first normalized -1 to 1 floats`() {
        // 2x2 image:
        // Pixel 0: pure Red   (255, 0, 0)     -> R = +1f, G = -1f, B = -1f
        // Pixel 1: pure Green (0, 255, 0)     -> R = -1f, G = +1f, B = -1f
        // Pixel 2: pure Blue  (0, 0, 255)     -> R = -1f, G = -1f, B = +1f
        // Pixel 3: Mid-gray   (127.5 -> 128)  -> (128/255 - 0.5) / 0.5
        val pixels = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFF808080.toInt()
        )

        val nchw = SiglipImagePreprocessor.argbToNormalizedNchw(pixels, width = 2, height = 2)
        assertEquals(3 * 4, nchw.size)

        val expectedMid = ((128f / 255f) - 0.5f) / 0.5f

        // Red plane (indices 0..3)
        assertEquals(1.0f, nchw[0], 1e-5f)
        assertEquals(-1.0f, nchw[1], 1e-5f)
        assertEquals(-1.0f, nchw[2], 1e-5f)
        assertEquals(expectedMid, nchw[3], 1e-5f)

        // Green plane (indices 4..7)
        assertEquals(-1.0f, nchw[4], 1e-5f)
        assertEquals(1.0f, nchw[5], 1e-5f)
        assertEquals(-1.0f, nchw[6], 1e-5f)
        assertEquals(expectedMid, nchw[7], 1e-5f)

        // Blue plane (indices 8..11)
        assertEquals(-1.0f, nchw[8], 1e-5f)
        assertEquals(-1.0f, nchw[9], 1e-5f)
        assertEquals(1.0f, nchw[10], 1e-5f)
        assertEquals(expectedMid, nchw[11], 1e-5f)

        // Retained LiteRT adapter delegates to the exact same NCHW preprocessing
        val liteRtNchw = LiteRtSiglipImageEmbedder.argbToNormalizedNchw(pixels, width = 2, height = 2)
        assertArrayEquals(nchw, liteRtNchw, 1e-6f)
    }

    @Test
    fun `tokenIdsToInt64 widens 32-bit BPE token IDs to 64-bit LongArray for ONNX input_ids`() {
        val tokenIds = intArrayOf(235250, 2686, 576, 476, 9786, 4401, 1, 0)
        val widened = SiglipImagePreprocessor.tokenIdsToInt64(tokenIds)

        assertArrayEquals(
            longArrayOf(235250L, 2686L, 576L, 476L, 9786L, 4401L, 1L, 0L),
            widened
        )
    }

    @Test
    fun `EmbeddingModelFactory defaults to ONNX_INT8 while retaining LITERT`() {
        assertEquals(EmbeddingModelFactory.Backend.ONNX_INT8, EmbeddingModelFactory.activeBackend)
        assertEquals(
            listOf(EmbeddingModelFactory.Backend.ONNX_INT8, EmbeddingModelFactory.Backend.LITERT),
            EmbeddingModelFactory.Backend.entries
        )
    }

    @Test
    fun `l2Normalize produces unit-length vector and handles zero vector safely`() {
        val raw = floatArrayOf(3.0f, 4.0f, 0.0f)
        val normalized = l2Normalize(raw)

        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
        assertEquals(0.0f, normalized[2], 1e-6f)

        val norm = sqrt(normalized.fold(0f) { acc, v -> acc + v * v })
        assertEquals(1.0f, norm, 1e-6f)

        val zero = floatArrayOf(0.0f, 0.0f, 0.0f)
        val normalizedZero = l2Normalize(zero)
        assertEquals(0.0f, normalizedZero[0], 1e-6f)
        assertEquals(0.0f, normalizedZero[1], 1e-6f)
        assertEquals(0.0f, normalizedZero[2], 1e-6f)
    }
}
