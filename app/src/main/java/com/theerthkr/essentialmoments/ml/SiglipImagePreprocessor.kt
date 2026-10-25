package com.theerthkr.essentialmoments.ml

import android.graphics.Bitmap

/**
 * Shared SigLIP 2 image preprocessing pipeline used across runtime adapters (ONNX Runtime, LiteRT).
 *
 * Encapsulates `224×224` bilinear scaling, temporary [Bitmap] recycling, and packed `0xAARRGGBB`
 * conversion into a channel-first [NCHW Tensor] (`[1, 3, 224, 224]`) normalized to `[-1.0, 1.0]`.
 */
object SiglipImagePreprocessor {

    const val TARGET_WIDTH = 224
    const val TARGET_HEIGHT = 224
    const val CHANNELS = 3

    // SigLIP rescales [0, 255] -> [0, 1] and normalizes with mean=0.5, std=0.5 into [-1, 1].
    private val SIGLIP_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val SIGLIP_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    private const val RESCALE_FACTOR = 255.0f

    /**
     * Scales [bitmap] to [TARGET_WIDTH]×[TARGET_HEIGHT], converts its pixels into a normalized
     * NCHW [FloatArray] of size `3 * 224 * 224` (`150,528`) in `[-1.0, 1.0]`, and recycles any
     * intermediate scaled [Bitmap].
     */
    fun preprocess(bitmap: Bitmap, onResized: ((Bitmap) -> Unit)? = null): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, TARGET_HEIGHT, true)
        return try {
            onResized?.invoke(resized)
            val argbPixels = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
            resized.getPixels(argbPixels, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
            argbToNormalizedNchw(argbPixels, TARGET_WIDTH, TARGET_HEIGHT)
        } finally {
            if (resized !== bitmap) {
                resized.recycle()
            }
        }
    }

    /**
     * Pure JVM-testable seam: transforms packed `0xAARRGGBB` pixels of size [width] * [height]
     * into a normalized NCHW [FloatArray] of size `3 * width * height` in `[-1.0, 1.0]`.
     */
    fun argbToNormalizedNchw(
        argbPixels: IntArray,
        width: Int = TARGET_WIDTH,
        height: Int = TARGET_HEIGHT
    ): FloatArray {
        require(argbPixels.size == width * height) {
            "Expected ${width * height} pixels, got ${argbPixels.size}"
        }
        val hwcPixels = extractHwcPixels(argbPixels)
        val rescaled = rescale(hwcPixels, RESCALE_FACTOR)
        val normalized = normalize(rescaled, SIGLIP_MEAN, SIGLIP_STD)
        return transposeHwcToNchw(normalized, width, height)
    }

    /**
     * Widens 32-bit token IDs from [SigLIPTokenizer] into a 64-bit [LongArray] (`int64`)
     * required by ONNX Runtime text models.
     */
    fun tokenIdsToInt64(tokenIds: IntArray): LongArray {
        return LongArray(tokenIds.size) { i -> tokenIds[i].toLong() }
    }

    internal fun extractHwcPixels(intValues: IntArray): FloatArray {
        val floatValues = FloatArray(intValues.size * CHANNELS)
        for (i in intValues.indices) {
            val pixel = intValues[i]
            floatValues[i * 3 + 0] = ((pixel shr 16) and 0xFF).toFloat() // Red
            floatValues[i * 3 + 1] = ((pixel shr 8) and 0xFF).toFloat()  // Green
            floatValues[i * 3 + 2] = (pixel and 0xFF).toFloat()          // Blue
        }
        return floatValues
    }

    internal fun rescale(pixels: FloatArray, scaleFactor: Float): FloatArray {
        val rescaled = FloatArray(pixels.size)
        for (i in pixels.indices) {
            rescaled[i] = pixels[i] / scaleFactor
        }
        return rescaled
    }

    internal fun normalize(pixels: FloatArray, mean: FloatArray, std: FloatArray): FloatArray {
        val normalized = FloatArray(pixels.size)
        for (i in pixels.indices step CHANNELS) {
            normalized[i + 0] = (pixels[i + 0] - mean[0]) / std[0] // R
            normalized[i + 1] = (pixels[i + 1] - mean[1]) / std[1] // G
            normalized[i + 2] = (pixels[i + 2] - mean[2]) / std[2] // B
        }
        return normalized
    }

    internal fun transposeHwcToNchw(hwcPixels: FloatArray, width: Int, height: Int): FloatArray {
        val nchwPixels = FloatArray(hwcPixels.size)
        val channelSize = width * height

        for (i in 0 until channelSize) {
            nchwPixels[i]                   = hwcPixels[i * 3 + 0] // Red plane
            nchwPixels[channelSize + i]     = hwcPixels[i * 3 + 1] // Green plane
            nchwPixels[channelSize * 2 + i] = hwcPixels[i * 3 + 2] // Blue plane
        }
        return nchwPixels
    }
}
