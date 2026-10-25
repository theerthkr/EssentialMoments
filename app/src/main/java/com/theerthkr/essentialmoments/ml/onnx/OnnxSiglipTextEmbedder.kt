package com.theerthkr.essentialmoments.ml.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import com.theerthkr.essentialmoments.l2Normalize
import com.theerthkr.essentialmoments.ml.SigLIPTokenizer
import com.theerthkr.essentialmoments.ml.SiglipImagePreprocessor
import com.theerthkr.essentialmoments.ml.TextEmbedder
import java.nio.LongBuffer

/**
 * ONNX Runtime INT8 adapter satisfying [TextEmbedder] for SigLIP 2 (`text_model_int8.onnx`).
 *
 * Uses [SigLIPTokenizer] to produce 64 BPE token IDs, widens them to `int64` (`[1, 64]`),
 * requests `pooler_output` (`[1, 768]`) from [OrtSession], and L2-normalizes the resulting vector.
 */
class OnnxSiglipTextEmbedder(context: Context) : TextEmbedder {

    override val modelId: String = MODEL_ID
    override val embeddingDimension: Int = EMBEDDING_DIM

    private val appContext = context.applicationContext
    private val tokenizer = SigLIPTokenizer(appContext)
    private var session: OrtSession? = null

    init {
        tokenizer.initialize()
        session = OnnxModelLoader.createSessionFromAsset(appContext, MODEL_FILE, TAG)
    }

    override fun getEmbedding(text: String): FloatArray {
        val tokenIds = tokenizer.tokenize(text)
        val inputIdsInt64 = SiglipImagePreprocessor.tokenIdsToInt64(tokenIds)
        val rawEmbedding = runInference(inputIdsInt64)
        return l2Normalize(rawEmbedding)
    }

    private fun runInference(inputIdsInt64: LongArray): FloatArray {
        val ortSession = checkNotNull(session) { "OnnxSiglipTextEmbedder session is closed" }
        val env = OnnxModelLoader.environment

        OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(inputIdsInt64),
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
        private const val TAG = "OnnxSiglipTextEmbedder"
        const val MODEL_ID = "siglip2_base_patch16_224_onnx_int8"
        const val EMBEDDING_DIM = 768
        const val MODEL_FILE = "text_model_int8.onnx"
        internal const val INPUT_NAME = "input_ids"
        internal const val OUTPUT_NAME = "pooler_output"
        private val REQUESTED_OUTPUTS = setOf(OUTPUT_NAME)
        private val INPUT_SHAPE = longArrayOf(1L, SigLIPTokenizer.MAX_LENGTH.toLong())
    }
}
