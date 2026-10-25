package com.theerthkr.essentialmoments.ml.litert

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.theerthkr.essentialmoments.l2Normalize
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.uriToBitmap

class LiteRtSiglipImageEmbedder(context: Context) : ImageEmbedder {

    override val modelId: String = MODEL_ID
    override val embeddingDimension: Int = EMBEDDING_DIM

    private val appContext = context.applicationContext
    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer>? = null
    private var outputBuffers: List<TensorBuffer>? = null

    init {
        val created = createCompiledModelWithFallback(appContext.assets, MODEL_FILE, TAG)
        model = created
        inputBuffers = created.createInputBuffers()
        outputBuffers = created.createOutputBuffers()
    }

    /**
     * Decodes [uri] into a [Bitmap], computes its 768-D L2-normalized SigLIP 2 embedding,
     * and recycles the decoded [Bitmap]. Returns `null` if the URI cannot be decoded.
     */
    override fun getEmbedding(uri: Uri): FloatArray? {
        val bitmap = uriToBitmap(appContext, uri) ?: return null
        return try {
            getEmbedding(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Computes a 768-D L2-normalized SigLIP 2 embedding from [bitmap].
     * Scales to 224x224, converts pixels to NCHW [-1, 1], runs LiteRT inference, and L2-normalizes.
     */
    override fun getEmbedding(bitmap: Bitmap, onResized: ((Bitmap) -> Unit)?): FloatArray {
        val resized = resize(bitmap, TARGET_WIDTH, TARGET_HEIGHT)
        val nchwFloatArray = try {
            onResized?.invoke(resized)
            val argbPixels = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
            resized.getPixels(argbPixels, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
            argbToNormalizedNchw(argbPixels, TARGET_WIDTH, TARGET_HEIGHT)
        } finally {
            if (resized !== bitmap) {
                resized.recycle()
            }
        }

        val rawOutput = runInference(nchwFloatArray)
        return l2Normalize(rawOutput)
    }

    private fun resize(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun runInference(nchwFloatArray: FloatArray): FloatArray {
        val m = checkNotNull(model) { "LiteRtSiglipImageEmbedder model is not initialized" }
        val ib = checkNotNull(inputBuffers) { "Input buffers not initialized" }
        val ob = checkNotNull(outputBuffers) { "Output buffers not initialized" }

        ib[0].writeFloat(nchwFloatArray)
        m.run(ib, ob)
        return ob[0].readFloat()
    }

    override fun close() {
        inputBuffers = null
        outputBuffers = null
        model?.close()
        model = null
    }

    internal companion object {
        private const val TAG = "LiteRtSiglipImageEmbedder"
        const val MODEL_ID = "siglip2_base_patch16_224_litert"
        const val EMBEDDING_DIM = 768
        private const val MODEL_FILE = "siglip2_base_patch16-224.tflite"
        internal const val TARGET_WIDTH = 224
        internal const val TARGET_HEIGHT = 224

        // SigLIP typically uses these values for normalization.
        // They bring the [0, 1] rescaled values into the [-1, 1] range.
        private val SIGLIP_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val SIGLIP_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private const val RESCALE_FACTOR = 255.0f

        /**
         * Pure JVM-testable seam: transforms packed 0xAARRGGBB pixels of size [width] * [height]
         * into a normalized NCHW [FloatArray] of size [3 * width * height] in [-1.0, 1.0].
         */
        internal fun argbToNormalizedNchw(
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
         * STEP 2: Extract raw pixels from packed ARGB ints into a flat FloatArray in HWC format.
         * (RGB interleaved: R, G, B, R, G, B...)
         */
        internal fun extractHwcPixels(intValues: IntArray): FloatArray {
            val floatValues = FloatArray(intValues.size * 3)
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
        internal fun rescale(pixels: FloatArray, scaleFactor: Float): FloatArray {
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
        internal fun normalize(pixels: FloatArray, mean: FloatArray, std: FloatArray): FloatArray {
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
        internal fun transposeHwcToNchw(hwcPixels: FloatArray, width: Int, height: Int): FloatArray {
            val nchwPixels = FloatArray(hwcPixels.size)
            val channelSize = width * height

            for (i in 0 until channelSize) {
                nchwPixels[i]                   = hwcPixels[i * 3 + 0] // Red channel block
                nchwPixels[channelSize + i]     = hwcPixels[i * 3 + 1] // Green channel block
                nchwPixels[channelSize * 2 + i] = hwcPixels[i * 3 + 2] // Blue channel block
            }
            return nchwPixels
        }
    }
}
