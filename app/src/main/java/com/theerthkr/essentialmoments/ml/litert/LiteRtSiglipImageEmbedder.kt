package com.theerthkr.essentialmoments.ml.litert

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.theerthkr.essentialmoments.l2Normalize
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.ml.SiglipImagePreprocessor
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
        val nchwFloatArray = SiglipImagePreprocessor.preprocess(bitmap, onResized)
        val rawOutput = runInference(nchwFloatArray)
        return l2Normalize(rawOutput)
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
        private const val MODEL_FILE = "litert/siglip2_base_patch16-224.tflite"
        internal const val TARGET_WIDTH = SiglipImagePreprocessor.TARGET_WIDTH
        internal const val TARGET_HEIGHT = SiglipImagePreprocessor.TARGET_HEIGHT

        internal fun argbToNormalizedNchw(
            argbPixels: IntArray,
            width: Int = TARGET_WIDTH,
            height: Int = TARGET_HEIGHT
        ): FloatArray = SiglipImagePreprocessor.argbToNormalizedNchw(argbPixels, width, height)
    }
}
