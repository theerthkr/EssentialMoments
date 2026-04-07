package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlin.math.sqrt

/**
 * Wraps SigLIP2 text encoder inference using LiteRT 2.1.0 CompiledModel API.
 *
 * Model: siglip2_text_only.tflite
 * Input : int32 [1, 64]  — token IDs from SigLIPTokenizer
 * Output: float32 [1, 768] — text embedding, L2-normalised before return
 */
class TextEmbedder(private val context: Context) {

    companion object {
        private const val TAG        = "TextEmbedder"
        private const val MODEL_FILE = "siglip2_text_only.tflite"
        const val EMBEDDING_DIM      = 768
    }

    private var model: CompiledModel? = null
    private val tokenizer = SigLIPTokenizer(context)

    // ── Init ──────────────────────────────────────────────────────

    fun initialize(debug: Boolean = false) {
        if (model != null) return
        tokenizer.initialize()

        val accelerators = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
        for (accel in accelerators) {
            model = tryCreate(accel, debug)
            if (model != null) {
                Log.d(TAG, "✅ TextEmbedder ready on $accel")
                return
            }
        }
        Log.e(TAG, "❌ All accelerators failed")
    }

    private fun tryCreate(accel: Accelerator, debug: Boolean): CompiledModel? {
        return try {
            CompiledModel.create(
                context.assets,
                MODEL_FILE,
                CompiledModel.Options(accel)
            )
        } catch (e: Exception) {
            if (debug) Log.w(TAG, "$accel unavailable: ${e.message}")
            null
        }
    }

    // ── Embed ─────────────────────────────────────────────────────

    /**
     * Converts [text] → tokenize → inference → L2-normalised FloatArray[768].
     * Returns null on failure.
     */
    fun embed(text: String, debug: Boolean = false): FloatArray? {
        val m = model ?: run { Log.e(TAG, "Not initialized"); return null }

        val tokenIds = tokenizer.tokenize(text)

        if (debug) {
            Log.d(TAG, "embed: '$text'")
            Log.d(TAG, "  token_ids[0..7]=${tokenIds.take(8).toIntArray().joinToString(",")}")
        }

        return runInference(m, tokenIds, debug)
    }

    // ── Inference ─────────────────────────────────────────────────

    private fun runInference(
        m: CompiledModel,
        tokenIds: IntArray,
        debug: Boolean
    ): FloatArray? {
        return try {
            val inputBuffers  = m.createInputBuffers()
            val outputBuffers = m.createOutputBuffers()

            // Text model takes int32 token IDs directly
            inputBuffers[0].writeInt(tokenIds)

            val t0 = System.currentTimeMillis()
            m.run(inputBuffers, outputBuffers)
            val ms = System.currentTimeMillis() - t0

            val raw = outputBuffers[0].readFloat()

            if (debug) {
                Log.d(TAG, "  inference: ${ms}ms  dim=${raw.size}")
                Log.d(TAG, "  raw[0..4]=${raw.take(5).joinToString { "%.4f".format(it) }}")
            }

            val normed = l2Normalize(raw)

            if (debug) {
                Log.d(TAG, "  L2_norm=${l2Norm(normed)}  (expect ≈ 1.0)")
            }

            normed
        } catch (e: Exception) {
            Log.e(TAG, "runInference failed: ${e.message}", e)
            null
        }
    }

    // ── Math ──────────────────────────────────────────────────────

    private fun l2Norm(v: FloatArray) = sqrt(v.fold(0f) { a, x -> a + x * x })

    private fun l2Normalize(v: FloatArray): FloatArray {
        val n = l2Norm(v)
        return if (n < 1e-8f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    // ── Debug ─────────────────────────────────────────────────────

    /**
     * Quick sanity check — embed the same query twice, cosine should be ≈ 1.0.
     * Also checks that "cat" and "dog" are similar but not identical.
     */
    fun debugSanityTest() {
        val e1 = embed("a cat sitting on a sofa", debug = true)
        val e2 = embed("a cat sitting on a sofa", debug = false)
        val e3 = embed("a dog running in a park", debug = false)

        if (e1 != null && e2 != null) {
            val selfSim = e1.zip(e2).fold(0f) { a, (x, y) -> a + x * y }
            Log.d(TAG, "selfSim(cat,cat)=$selfSim  (expect ≈ 1.0)")
        }
        if (e1 != null && e3 != null) {
            val crossSim = e1.zip(e3).fold(0f) { a, (x, y) -> a + x * y }
            Log.d(TAG, "crossSim(cat,dog)=$crossSim  (expect 0.7–0.9, similar but not 1.0)")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    fun close() {
        model = null
        Log.d(TAG, "TextEmbedder closed")
    }
}