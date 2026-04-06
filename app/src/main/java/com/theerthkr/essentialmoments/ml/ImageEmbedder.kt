package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlin.math.sqrt

/**
 * Wraps SigLIP2 image model inference using LiteRT 2.1.0 CompiledModel API.
 *
 * Usage:
 *   val embedder = ImageEmbedder(context)
 *   embedder.initialize()
 *   val vec = embedder.embed(uri)           // FloatArray[768], L2-normalised
 */
class ImageEmbedder(private val context: Context) {

    companion object {
        private const val TAG        = "ImageEmbedder"
        private const val MODEL_FILE = "siglip2_base_patch16-224_f16.tflite"
        const val EMBEDDING_DIM      = 768
    }

    private var model: CompiledModel? = null
    private val preprocessor = ImagePreprocessor(context)

    // ─────────────────────────────────────────────────────────────
    // Initialisation  (call once — from a background coroutine)
    // ─────────────────────────────────────────────────────────────

    fun initialize(debug: Boolean = false) {
        if (model != null) return

        // Mirror what your ModelActivity already does successfully
        val acceleratorOrder = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)

        for (accel in acceleratorOrder) {
            model = tryCreate(accel, debug)
            if (model != null) {
                Log.d(TAG, "✅ Model loaded on $accel")
                if (debug) logTensorInfo()
                return
            }
        }
        Log.e(TAG, "❌ All accelerators failed — model not loaded")
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

    // ─────────────────────────────────────────────────────────────
    // Embed from URI  (your MediaImage.uri path)
    // ─────────────────────────────────────────────────────────────

    fun embed(uri: Uri, debug: Boolean = false): FloatArray? {
        val m = model ?: run { Log.e(TAG, "Not initialised"); return null }

        val buffer = preprocessor.preprocess(uri, debug) ?: return null

        // Convert ByteBuffer → FloatArray for CompiledModel.writeFloat
        // CompiledModel expects float32 input even though model weights are f16
        // We pass the raw bytes via writeFloat after converting back to f32
        val floatInput = bufferToFloat32Array(buffer)

        return runInference(m, floatInput, debug)
    }

    fun embed(bitmap: Bitmap, debug: Boolean = false): FloatArray? {
        val m = model ?: run { Log.e(TAG, "Not initialised"); return null }
        val buffer = preprocessor.preprocessBitmap(bitmap, debug)
        val floatInput = bufferToFloat32Array(buffer)
        return runInference(m, floatInput, debug)
    }

    // ─────────────────────────────────────────────────────────────
    // Core inference
    // ─────────────────────────────────────────────────────────────

    private fun runInference(
        m: CompiledModel,
        floatInput: FloatArray,
        debug: Boolean
    ): FloatArray? {
        return try {
            val inputBuffers  = m.createInputBuffers()
            val outputBuffers = m.createOutputBuffers()

            inputBuffers[0].writeFloat(floatInput)

            val t0 = System.currentTimeMillis()
            m.run(inputBuffers, outputBuffers)
            val ms = System.currentTimeMillis() - t0

            val raw = outputBuffers[0].readFloat()

            if (debug) {
                Log.d(TAG, "✅ Inference: ${ms}ms  output_dim=${raw.size}")
                Log.d(TAG, "   raw[0..4]=${raw.take(5).joinToString { "%.4f".format(it) }}")
                Log.d(TAG, "   L2_norm_before_normalise=${"%.6f".format(l2Norm(raw))}")
            }

            val normed = l2Normalize(raw)

            if (debug) {
                Log.d(TAG, "   L2_norm_after_normalise=${"%.6f".format(l2Norm(normed))}  (expect ≈ 1.0)")
            }

            normed
        } catch (e: Exception) {
            Log.e(TAG, "runInference failed: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Buffer conversion helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Our preprocessor packs float16 bytes (matching the model's native dtype).
     * CompiledModel.writeFloat() expects float32, so we decode f16 → f32 here.
     */
    private fun bufferToFloat32Array(buf: java.nio.ByteBuffer): FloatArray {
        buf.rewind()
        val numValues = buf.capacity() / 2  // 2 bytes per float16
        val out = FloatArray(numValues)
        for (i in 0 until numValues) {
            out[i] = f16ToF32(buf.short)
        }
        return out
    }

    private fun f16ToF32(h: Short): Float {
        val hBits = h.toInt() and 0xFFFF
        val sign  = (hBits shr 15) and 0x1
        val exp   = (hBits shr 10) and 0x1F
        val frac  =  hBits         and 0x3FF
        val f32Bits = when {
            exp == 0    -> (sign shl 31) or (frac shl 13)
            exp == 31   -> (sign shl 31) or 0x7F800000 or (frac shl 13)
            else        -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (frac shl 13)
        }
        return java.lang.Float.intBitsToFloat(f32Bits)
    }

    // ─────────────────────────────────────────────────────────────
    // Math
    // ─────────────────────────────────────────────────────────────

    fun l2Norm(v: FloatArray) = sqrt(v.fold(0f) { a, x -> a + x * x })

    fun l2Normalize(v: FloatArray): FloatArray {
        val n = l2Norm(v)
        return if (n < 1e-8f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    /** Dot-product of two unit vectors = cosine similarity */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var s = 0f; for (i in a.indices) s += a[i] * b[i]
        return s
    }

    // ─────────────────────────────────────────────────────────────
    // Debug
    // ─────────────────────────────────────────────────────────────

    private fun logTensorInfo() {
        // CompiledModel doesn't expose tensor metadata directly,
        // but you can verify via the debug inference test below
        Log.d(TAG, "Model loaded. Expected input: [1,224,224,3] f16  Output: [1,$EMBEDDING_DIM] f32")
    }

    /** Run the same bitmap twice — cosine should be ≈ 1.000 */
    fun selfSimilarityTest(bitmap: Bitmap): Float {
        val e1 = embed(bitmap, debug = true)  ?: return -1f
        val e2 = embed(bitmap, debug = false) ?: return -1f
        val sim = cosineSimilarity(e1, e2)
        Log.d(TAG, "selfSimilarityTest → cosine=$sim  (expect ≈ 1.0)")
        return sim
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    fun close() {
        model = null
        Log.d(TAG, "ImageEmbedder closed")
    }
}