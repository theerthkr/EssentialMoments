package com.theerthkr.essentialmoments.ml.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.theerthkr.essentialmoments.l2Normalize
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.ml.SiglipImagePreprocessor
import com.theerthkr.essentialmoments.uriToBitmap
import java.nio.FloatBuffer

/**
 * ONNX Runtime INT8 adapter satisfying [ImageEmbedder] for SigLIP 2 (`vision_model_int8.onnx`).
 *
 * Delegates `224×224` NCHW `[-1.0, 1.0]` preprocessing to [SiglipImagePreprocessor], executes
 * `pixel_values -> pooler_output` via [OrtSession], and L2-normalizes the resulting 768-D vector.
 */
class OnnxSiglipImageEmbedder(context: Context) : ImageEmbedder {

    override val modelId: String = MODEL_ID
    override val embeddingDimension: Int = EMBEDDING_DIM

    private val appContext = context.applicationContext
    private var session: OrtSession? = OnnxModelLoader.createSessionFromAsset(
        appContext,
        MODEL_FILE,
        TAG
    )

    override fun getEmbedding(uri: Uri): FloatArray? {
        val bitmap = uriToBitmap(appContext, uri) ?: return null
        return try {
            getEmbedding(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun getEmbedding(bitmap: Bitmap, onResized: ((Bitmap) -> Unit)?): FloatArray {
        val nchwFloatArray = SiglipImagePreprocessor.preprocess(bitmap, onResized)
        val rawOutput = runInference(nchwFloatArray)
        return l2Normalize(rawOutput)
    }

    private fun runInference(nchwFloatArray: FloatArray): FloatArray {
        val ortSession = checkNotNull(session) { "OnnxSiglipImageEmbedder session is closed" }
        val env = OnnxModelLoader.environment

        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(nchwFloatArray),
            INPUT_SHAPE
        ).use { inputTensor ->
            ortSession.run(
                mapOf(INPUT_NAME to inputTensor),
                REQUESTED_OUTPUTS
            ).use { result ->
                return OnnxModelLoader.extractFloatOutput(result, EMBEDDING_DIM)
            }
        }
    }

    override fun close() {
        session?.close()
        session = null
    }

    companion object {
        private const val TAG = "OnnxSiglipImageEmbedder"
        const val MODEL_ID = "siglip2_base_patch16_224_onnx_int8"
        const val EMBEDDING_DIM = 768
        const val MODEL_FILE = "onnx_int8/vision_model_int8.onnx"
        internal const val INPUT_NAME = "pixel_values"
        internal const val OUTPUT_NAME = "pooler_output"
        private val REQUESTED_OUTPUTS = setOf(OUTPUT_NAME)
        private val INPUT_SHAPE = longArrayOf(
            1L,
            SiglipImagePreprocessor.CHANNELS.toLong(),
            SiglipImagePreprocessor.TARGET_HEIGHT.toLong(),
            SiglipImagePreprocessor.TARGET_WIDTH.toLong()
        )
    }
}
