package com.theerthkr.essentialmoments.ml.litert

import android.content.Context
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.theerthkr.essentialmoments.l2Normalize
import com.theerthkr.essentialmoments.ml.SigLIPTokenizer
import com.theerthkr.essentialmoments.ml.TextEmbedder

class LiteRtSiglipTextEmbedder(context: Context) : TextEmbedder {

    override val modelId: String = MODEL_ID
    override val embeddingDimension: Int = EMBEDDING_DIM

    companion object {
        private const val TAG = "LiteRtSiglipTextEmbedder"
        const val MODEL_ID = "siglip2_base_patch16_224_litert"
        const val EMBEDDING_DIM = 768
        private const val MODEL_FILE = "litert/siglip2_text.tflite"
    }

    private val tokenizer = SigLIPTokenizer(context.applicationContext)
    private var model: CompiledModel? = null
    private var inputBuffers: List<TensorBuffer>? = null
    private var outputBuffers: List<TensorBuffer>? = null

    init {
        // 1. Load the pure-Kotlin SigLIP 2 BPE tokenizer (cached across instances)
        tokenizer.initialize()

        // 2. Load the LiteRT Model with accelerator fallback (NPU -> GPU -> CPU)
        val created = createCompiledModelWithFallback(context.assets, MODEL_FILE, TAG)
        model = created
        inputBuffers = created.createInputBuffers()
        outputBuffers = created.createOutputBuffers()
    }

    /**
     * MASTER FUNCTION: Takes a string and returns a normalized 768-D FloatArray
     */
    override fun getEmbedding(text: String): FloatArray {
        // 1. Tokenize text (pure Kotlin BPE handles lowercasing, EOS token, and 64-token padding)
        val inputIds = tokenizeAndPad(text)

        // 2. Run Inference
        val rawEmbedding = runInference(inputIds)

        // 3. Normalize for Cosine Similarity
        return l2Normalize(rawEmbedding)
    }

    /**
     * Converts text to a strict IntArray of size [64]
     */
    private fun tokenizeAndPad(text: String): IntArray {
        val finalArray = tokenizer.tokenize(text)
        println("ANDROID TOKENS: ${finalArray.take(15).joinToString(", ")}")
        return finalArray
    }

    /**
     * Feeds the [64] IntArray into LiteRT and extracts the [768] FloatArray
     */
    private fun runInference(inputArray: IntArray): FloatArray {
        val m = checkNotNull(model) { "LiteRtSiglipTextEmbedder model is not initialized" }
        val ib = checkNotNull(inputBuffers) { "Input buffers not initialized" }
        val ob = checkNotNull(outputBuffers) { "Output buffers not initialized" }

        // Write the [64] tokens into the pre-allocated input buffer
        ib[0].writeInt(inputArray)

        // Execute math
        m.run(ib, ob)

        // Read the [768] output
        return ob[0].readFloat()
    }

    /**
     * Call this when your app closes or Activity is destroyed to free up memory
     */
    override fun close() {
        inputBuffers = null
        outputBuffers = null
        model?.close()
        model = null
    }
}
