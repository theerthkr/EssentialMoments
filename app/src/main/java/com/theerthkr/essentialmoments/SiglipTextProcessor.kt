package com.theerthkr.essentialmoments

import android.content.Context
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlin.math.sqrt

class SiglipTextProcessor(context: Context) {

    private val tokenizer: HuggingFaceTokenizer
    private val model: CompiledModel

    // TFLite Text Model constraints
    private val maxLength = 64
    private val padTokenId = 0 // Standard pad token for SigLIP

    init {
        // 1. Load the Tokenizer
        // DJL can read the JSON file directly from an InputStream
        val tokenizerStream = context.assets.open("tokenizer.json")
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerStream, null)

        // 2. Load the LiteRT Model
        model = CompiledModel.create(
            context.assets,
            "siglip2_text.tflite",
            CompiledModel.Options(Accelerator.NPU) // Fallback to CPU happens automatically if NPU fails
        )
    }

    /**
     * MASTER FUNCTION: Takes a string and returns a normalized 768-D FloatArray
     */
    fun getEmbedding(text: String): FloatArray {
        // 1. Tokenize text (DJL handles lowercasing and EOS tokens automatically)
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
        val encoding = tokenizer.encode(text)
        val rawIds = encoding.ids // This is a LongArray

        // Create an array of exactly 64 padding tokens
        val finalArray = IntArray(maxLength) { padTokenId }

        // Copy our actual text tokens into the padded array (up to 64)
        val limit = minOf(rawIds.size, maxLength)
        for (i in 0 until limit) {
            finalArray[i] = rawIds[i].toInt()
        }
        println("ANDROID TOKENS: ${finalArray.take(15).joinToString(", ")}")
        return finalArray
    }

    /**
     * Feeds the [64] IntArray into LiteRT and extracts the [768] FloatArray
     */
    private fun runInference(inputArray: IntArray): FloatArray {
        val inputBuffers = model.createInputBuffers()
        val outputBuffers = model.createOutputBuffers()

        // Write the [64] tokens into the model
        inputBuffers[0].writeInt(inputArray)

        // Execute math
        model.run(inputBuffers, outputBuffers)

        // Read the [768] output
        return outputBuffers[0].readFloat()
    }

    /**
     * Normalizes the vector so it can be compared with the Image vector
     */
    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var sumOfSquares = 0.0f
        for (value in embedding) {
            sumOfSquares += value * value
        }

        val magnitude = sqrt(sumOfSquares)
        if (magnitude == 0.0f) return embedding

        val normalized = FloatArray(embedding.size)
        for (i in embedding.indices) {
            normalized[i] = embedding[i] / magnitude
        }
        return normalized
    }

    /**
     * Call this when your app closes or Activity is destroyed to free up memory
     */
    fun close() {
        tokenizer.close()
        model.close()
    }
}