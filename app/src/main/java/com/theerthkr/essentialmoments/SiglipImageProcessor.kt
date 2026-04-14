package com.theerthkr.essentialmoments

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SiglipImageProcessor {

    // SigLIP typically uses these values for normalization.
    // They bring the [0, 1] rescaled values into the [-1, 1] range.
    private val siglipMean = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val siglipStd = floatArrayOf(0.5f, 0.5f, 0.5f)

    /**
     * MASTER FUNCTION: Chains all the individual steps together.
     * Returns a ByteBuffer ready to be fed directly into your TFLite model.
     */
    fun preprocess(bitmap: Bitmap): ByteBuffer {
        // 1. Resize
        val resizedBitmap = resize(bitmap, 224, 224)

        // 2. Extract pixels to HWC FloatArray (values 0.0 - 255.0)
        val hwcPixels = extractHwcPixels(resizedBitmap)

        // 3. Rescale (values 0.0 - 1.0)
        val rescaledPixels = rescale(hwcPixels, 255.0f)

        // 4. Normalize
        val normalizedPixels = normalize(rescaledPixels, siglipMean, siglipStd)

        // 5. Transpose to NCHW (Model requirement: [1, 3, 224, 224])
        val nchwPixels = transposeHwcToNchw(normalizedPixels, 224, 224)

        // 6. Convert to ByteBuffer
        return toByteBuffer(nchwPixels)
    }

    /**
     * STEP 1: Resize the bitmap to the target dimensions.
     */
    fun resize(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * STEP 2: Extract raw pixels from a Bitmap into a flat FloatArray in HWC format.
     * (RGB interleaved: R, G, B, R, G, B...)
     */
    fun extractHwcPixels(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        val floatValues = FloatArray(width * height * 3)
        for (i in intValues.indices) {
            val pixel = intValues[i]
            // Extract RGB, ignoring Alpha
            floatValues[i * 3 + 0] = ((pixel shr 16) and 0xFF).toFloat() // Red
            floatValues[i * 3 + 1] = ((pixel shr 8) and 0xFF).toFloat()  // Green
            floatValues[i * 3 + 2] = (pixel and 0xFF).toFloat()          // Blue
        }
        return floatValues
    }

    /**
     * STEP 3: Rescale the pixel values.
     * Usually divides by 255f to bring range from [0, 255] to [0, 1].
     */
    fun rescale(pixels: FloatArray, scaleFactor: Float): FloatArray {
        val rescaled = FloatArray(pixels.size)
        for (i in pixels.indices) {
            rescaled[i] = pixels[i] / scaleFactor
        }
        return rescaled
    }

    /**
     * STEP 4: Normalize the channels using Mean and Standard Deviation.
     * Formula: (pixel_value - mean) / std
     */
    fun normalize(pixels: FloatArray, mean: FloatArray, std: FloatArray): FloatArray {
        val normalized = FloatArray(pixels.size)
        for (i in pixels.indices step 3) {
            normalized[i + 0] = (pixels[i + 0] - mean[0]) / std[0] // R
            normalized[i + 1] = (pixels[i + 1] - mean[1]) / std[1] // G
            normalized[i + 2] = (pixels[i + 2] - mean[2]) / std[2] // B
        }
        return normalized
    }

    /**
     * STEP 5: Transpose the flat array from HWC to NCHW format.
     * Converts: [R,G,B, R,G,B] -> [R,R..., G,G..., B,B...]
     */
    fun transposeHwcToNchw(hwcPixels: FloatArray, width: Int, height: Int): FloatArray {
        val nchwPixels = FloatArray(hwcPixels.size)
        val channelSize = width * height

        for (i in 0 until channelSize) {
            nchwPixels[i]                   = hwcPixels[i * 3 + 0] // Red channel block
            nchwPixels[channelSize + i]     = hwcPixels[i * 3 + 1] // Green channel block
            nchwPixels[channelSize * 2 + i] = hwcPixels[i * 3 + 2] // Blue channel block
        }
        return nchwPixels
    }

    /**
     * STEP 6: Convert the final FloatArray to a ByteBuffer.
     * TFLite requires ByteBuffers allocated with native byte order for performance.
     */
    fun toByteBuffer(floatArray: FloatArray): ByteBuffer {
        // 4 bytes per float
        val byteBuffer = ByteBuffer.allocateDirect(floatArray.size * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        for (value in floatArray) {
            byteBuffer.putFloat(value)
        }
        // Rewind the buffer so it's ready to be read by TFLite
        byteBuffer.rewind()
        return byteBuffer
    }
}