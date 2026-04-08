package com.theerthkr.essentialmoments.ml

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlin.math.sqrt

/**
 * Wraps SigLIP2 image model inference using LiteRT 2.1.0 CompiledModel API.
 *
 * Pipeline:
 *   URI (content://) → ImagePreprocessor → f16 ByteBuffer
 *   → f32 FloatArray → CompiledModel.writeFloat
 *   → raw FloatArray[768] → L2-normalise → unit vector
 *
 * Debug checkpoints (enabled via debug=true or initialize(debug=true)):
 *   [D5] Inference timing + delegate used
 *   [D6] L2 norm before and after normalisation (expect ≈ 1.0 after)
 *
 * Usage:
 *   val embedder = ImageEmbedder(context)
 *   embedder.initialize()                         // call once on a BG thread
 *   val vec = embedder.embed(contentUri)          // FloatArray[768], L2-normalised
 *   embedder.close()                              // call in onDestroy / onCleared
 */
class ImageEmbedder(private val context: Context) {

    companion object {
        private const val TAG        = "ImageEmbedder"
        private const val MODEL_FILE = "siglip2_base_patch16-224_f16.tflite"
        const val EMBEDDING_DIM      = 768

        /**
         * Converts a raw MediaStore image ID (Long) to a proper content:// URI.
         * Use this everywhere instead of MediaStore.Images.Media.DATA paths,
         * which are unreliable on Android 10+ (scoped storage).
         */
        fun idToContentUri(imageId: Long): Uri =
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageId
            )
    }

    private var model: CompiledModel? = null
    private var activeAccelerator: Accelerator? = null
    val preprocessor = ImagePreprocessor(context)   // internal for test access

    // ─────────────────────────────────────────────────────────────
    // Initialisation  (call once — always from a background coroutine)
    // ─────────────────────────────────────────────────────────────

    /**
     * Loads the TFLite model, trying NPU → GPU → CPU in order.
     * Safe to call multiple times (no-op after first success).
     */
    fun initialize(debug: Boolean = false) {
        if (model != null) {
            if (debug) Log.d(TAG, "Already initialized on $activeAccelerator")
            return
        }

        val acceleratorOrder = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)

        for (accel in acceleratorOrder) {
            val m = tryCreate(accel, debug)
            if (m != null) {
                model = m
                activeAccelerator = accel
                Log.d(TAG, "✅ Model loaded on $accel")
                if (debug) logModelInfo()
                return
            }
        }
        Log.e(TAG, "❌ All accelerators failed — model not loaded. Check assets/$MODEL_FILE exists.")
    }

    private fun tryCreate(accel: Accelerator, debug: Boolean): CompiledModel? {
        return try {
            CompiledModel.create(
                context.assets,
                MODEL_FILE,
                CompiledModel.Options(accel)
            )
        } catch (e: Exception) {
            if (debug) Log.w(TAG, "  $accel unavailable: ${e.message}")
            null
        }
    }

    val isInitialized: Boolean get() = model != null
    val activeDelegate: String get() = activeAccelerator?.name ?: "none"

    // ─────────────────────────────────────────────────────────────
    // Embed from content:// URI
    // ─────────────────────────────────────────────────────────────

    /**
     * Full pipeline for a single image URI.
     * The URI MUST be a content:// URI (use idToContentUri() to convert IDs).
     * Raw file paths (from MediaStore.DATA) will fail on Android 10+.
     *
     * Returns null if the model isn't loaded or the image can't be decoded.
     */
    fun embed(uri: Uri, debug: Boolean = false): FloatArray? {
        val m = model ?: run {
            Log.e(TAG, "embed() called before initialize()")
            return null
        }

        val buffer = preprocessor.preprocess(uri, debug) ?: run {
            Log.e(TAG, "Preprocessing failed for uri=$uri")
            return null
        }

        val floatInput = bufferToFloat32Array(buffer)
        return runInference(m, floatInput, debug)
    }

    /**
     * Embed directly from a Bitmap (used by selfSimilarityTest + ModelActivity).
     * Does NOT recycle the input bitmap.
     */
    fun embed(bitmap: Bitmap, debug: Boolean = false): FloatArray? {
        val m = model ?: run {
            Log.e(TAG, "embed(bitmap) called before initialize()")
            return null
        }
        val buffer = preprocessor.preprocessBitmap(bitmap, debug)
        val floatInput = bufferToFloat32Array(buffer)
        return runInference(m, floatInput, debug)
    }

    // ─────────────────────────────────────────────────────────────
    // Core inference  [Debug D5: timing + delegate] [D6: L2 norm]
    // ─────────────────────────────────────────────────────────────

    private fun runInference(
        m: CompiledModel,
        floatInput: FloatArray,
        debug: Boolean
    ): FloatArray? {
        return try {
            val inputBuffers  = m.createInputBuffers()
            val outputBuffers = m.createOutputBuffers()

            // CompiledModel.writeFloat() expects float32 — we've already decoded f16→f32
            inputBuffers[0].writeFloat(floatInput)

            val t0 = System.currentTimeMillis()
            m.run(inputBuffers, outputBuffers)
            val ms = System.currentTimeMillis() - t0

            val raw = outputBuffers[0].readFloat()

            // [D5] Inference timing + delegate
            if (debug) {
                Log.d(TAG, "[D5] Inference: ${ms}ms  delegate=$activeAccelerator  " +
                        "output_dim=${raw.size} (expect $EMBEDDING_DIM)")
                Log.d(TAG, "[D5] raw[0..4]=${raw.take(5).joinToString { "%.4f".format(it) }}")
                if (raw.size != EMBEDDING_DIM) {
                    Log.e(TAG, "[D5] ❌ unexpected output dim: ${raw.size}")
                }
            }

            val normBefore = l2Norm(raw)
            val normed = l2Normalize(raw)
            val normAfter = l2Norm(normed)

            // [D6] L2 norm check
            if (debug) {
                Log.d(TAG, "[D6] L2 norm before normalise: ${"%.6f".format(normBefore)}")
                Log.d(TAG, "[D6] L2 norm after  normalise: ${"%.6f".format(normAfter)}  " +
                        "(expect ≈ 1.0) ${if (normAfter > 0.999f && normAfter < 1.001f) "✅" else "⚠️"}")
                Log.d(TAG, "[D6] normed[0..4]=${normed.take(5).joinToString { "%.4f".format(it) }}")
            }

            normed

        } catch (e: Exception) {
            Log.e(TAG, "runInference failed: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // f16 ByteBuffer → f32 FloatArray
    // ─────────────────────────────────────────────────────────────

    /**
     * Our preprocessor packs float16 bytes (matching the model's native dtype).
     * CompiledModel.writeFloat() expects float32, so we decode f16 → f32 here.
     */
    private fun bufferToFloat32Array(buf: java.nio.ByteBuffer): FloatArray {
        buf.rewind()
        // 2 bytes per f16 value
        val numValues = buf.capacity() / 2
        val out = FloatArray(numValues)
        for (i in 0 until numValues) {
            out[i] = preprocessor.f16ToF32(buf.short)
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────
    // Math utilities (also used externally by ModelActivity)
    // ─────────────────────────────────────────────────────────────

    fun l2Norm(v: FloatArray) = sqrt(v.fold(0f) { a, x -> a + x * x })

    fun l2Normalize(v: FloatArray): FloatArray {
        val n = l2Norm(v)
        return if (n < 1e-8f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    /** Cosine similarity of two L2-normalised vectors = their dot product */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector size mismatch: ${a.size} vs ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    // ─────────────────────────────────────────────────────────────
    // Debug helpers
    // ─────────────────────────────────────────────────────────────

    private fun logModelInfo() {
        Log.d(TAG, "Model info:")
        Log.d(TAG, "  file     : $MODEL_FILE")
        Log.d(TAG, "  input    : [1, 224, 224, 3]  dtype=float16")
        Log.d(TAG, "  output   : [1, $EMBEDDING_DIM]  dtype=float32")
        Log.d(TAG, "  delegate : $activeAccelerator")
    }

    /**
     * Run the same bitmap twice and check cosine similarity ≈ 1.000.
     * A result < 0.9999 suggests a non-deterministic delegate (some GPU delegates).
     */
    fun selfSimilarityTest(bitmap: Bitmap): Float {
        val e1 = embed(bitmap, debug = true)  ?: return -1f
        val e2 = embed(bitmap, debug = false) ?: return -1f
        val sim = cosineSimilarity(e1, e2)
        Log.d(TAG, "selfSimilarityTest → cosine=${"%.6f".format(sim)}  " +
                "(expect ≈ 1.0; < 0.9999 suggests delegate non-determinism)")
        return sim
    }

    /**
     * Embed two different images and log their cosine similarity.
     * Useful to verify the model discriminates between unlike images.
     */
    fun crossSimilarityTest(uriA: Uri, uriB: Uri): Float {
        val eA = embed(uriA, debug = false) ?: return -1f
        val eB = embed(uriB, debug = false) ?: return -1f
        val sim = cosineSimilarity(eA, eB)
        Log.d(TAG, "crossSimilarityTest → cosine=${"%.6f".format(sim)}  " +
                "(similar images ≈ 0.9+, unlike images ≈ 0.5–0.7)")
        return sim
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    fun close() {
        model = null
        activeAccelerator = null
        Log.d(TAG, "ImageEmbedder closed")
    }
}
