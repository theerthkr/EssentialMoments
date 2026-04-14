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
 * KEY FIX — buffer reuse:
 *
 * WRONG (previous): createInputBuffers() + createOutputBuffers() called every inference.
 *   → Allocates new GPU shared-memory buffers on every image.
 *   → Old buffers not freed until GC runs (which lags behind inference speed).
 *   → GPU buffer pool exhausted after ~768 images → LiteRtException at createInputBuffers().
 *   → Continued pressure → OS OOM kill.
 *
 * CORRECT (this version): allocate buffers ONCE after model load, reuse for every inference.
 *   CompiledModel is explicitly designed for this pattern — the buffers are
 *   mutable containers that get overwritten on each run() call.
 *   GPU memory stays flat at ~constant overhead regardless of image count.
 */
class ImageEmbedder(
    private val context: Context,
    private val workerMode: Boolean = false
) {
    companion object {
        private const val TAG        = "ImageEmbedder"
        private const val MODEL_FILE = "siglip2_base_patch16-224_f16.tflite"
        const val EMBEDDING_DIM      = 768

        fun idToContentUri(imageId: Long): Uri =
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId
            )
    }

    private var model: CompiledModel? = null

    // Allocated ONCE after model load, reused for every inference call.
    // This is the fix — previously these were created inside runInference() per call.
    private var inputBuffers:  List<com.google.ai.edge.litert.TensorBuffer>? = null
    private var outputBuffers: List<com.google.ai.edge.litert.TensorBuffer>? = null

    private var activeAccelDesc: String = "none"
    val preprocessor = ImagePreprocessor(context)

    val isInitialized: Boolean get() = model != null
    val activeDelegate: String  get() = activeAccelDesc

    // ── Init ──────────────────────────────────────────────────────

    fun initialize(debug: Boolean = false) {
        if (model != null) return

        val attempts: List<Pair<String, CompiledModel.Options>> = if (workerMode) {
            listOf(
                "GPU+CPU" to CompiledModel.Options(Accelerator.GPU, Accelerator.CPU),
                "CPU"     to CompiledModel.Options(Accelerator.CPU)
            )
        } else {
            listOf(
                "NPU"     to CompiledModel.Options(Accelerator.NPU),
                "GPU+CPU" to CompiledModel.Options(Accelerator.GPU, Accelerator.CPU),
                "CPU"     to CompiledModel.Options(Accelerator.CPU)
            )
        }

        for ((name, opts) in attempts) {
            val m = try {
                CompiledModel.create(context.assets, MODEL_FILE, opts)
            } catch (e: Exception) {
                if (debug) Log.w(TAG, "$name failed: ${e.message}"); null
            }
            if (m != null) {
                model = m
                activeAccelDesc = name
                // Allocate buffers ONCE here — reused for the entire lifetime of this embedder
                inputBuffers  = m.createInputBuffers()
                outputBuffers = m.createOutputBuffers()
                Log.d(TAG, "✅ ImageEmbedder on $name  (buffers allocated once)")
                return
            }
        }
        Log.e(TAG, "❌ All accelerator options failed")
    }

    // ── Embed ─────────────────────────────────────────────────────

    fun embed(uri: Uri, debug: Boolean = false): FloatArray? {
        val m  = model         ?: run { Log.e(TAG, "Not initialized"); return null }
        val ib = inputBuffers  ?: return null
        val ob = outputBuffers ?: return null
        val buf = preprocessor.preprocess(uri, debug) ?: return null
        return runInference(m, ib, ob, bufferToFloat32(buf), debug)
    }

    fun embed(bitmap: Bitmap, debug: Boolean = false): FloatArray? {
        val m  = model         ?: run { Log.e(TAG, "Not initialized"); return null }
        val ib = inputBuffers  ?: return null
        val ob = outputBuffers ?: return null
        val buf = preprocessor.preprocessBitmap(bitmap, debug)
        return runInference(m, ib, ob, bufferToFloat32(buf), debug)
    }

    // ── Inference — uses pre-allocated buffers ─────────────────────

    private fun runInference(
        m: CompiledModel,
        ib: List<com.google.ai.edge.litert.TensorBuffer>,
        ob: List<com.google.ai.edge.litert.TensorBuffer>,
        input: FloatArray,
        debug: Boolean
    ): FloatArray? = try {
        // Write into the SAME buffer objects — no new allocation
        ib[0].writeFloat(input)
        val t0 = System.currentTimeMillis()
        m.run(ib, ob)
        val ms  = System.currentTimeMillis() - t0
        // Read from the SAME output buffer — no new allocation
        val raw = ob[0].readFloat()

        if (debug) {
            Log.d(TAG, "[D5] ${ms}ms  delegate=$activeAccelDesc  dim=${raw.size}")
            Log.d(TAG, "[D6] L2_before=${"%.4f".format(l2Norm(raw))}")
        }
        val normed = l2Normalize(raw)
        if (debug) Log.d(TAG, "[D6] L2_after=${"%.6f".format(l2Norm(normed))}")
        normed
    } catch (e: Exception) {
        Log.e(TAG, "Inference failed: ${e.message}", e); null
    }

    private fun bufferToFloat32(buf: java.nio.ByteBuffer): FloatArray {
        buf.rewind()
        return FloatArray(buf.capacity() / 2) { preprocessor.f16ToF32(buf.short) }
    }

    // ── Math ──────────────────────────────────────────────────────

    fun l2Norm(v: FloatArray) = sqrt(v.fold(0f) { a, x -> a + x * x })
    fun l2Normalize(v: FloatArray): FloatArray {
        val n = l2Norm(v)
        return if (n < 1e-8f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
    }

    fun selfSimilarityTest(bitmap: Bitmap): Float {
        val e1 = embed(bitmap, debug = true)  ?: return -1f
        val e2 = embed(bitmap, debug = false) ?: return -1f
        return cosineSimilarity(e1, e2).also {
            Log.d(TAG, "selfSim=${"%.6f".format(it)} (expect≈1.0)")
        }
    }

    fun crossSimilarityTest(uriA: Uri, uriB: Uri): Float {
        val eA = embed(uriA) ?: return -1f
        val eB = embed(uriB) ?: return -1f
        return cosineSimilarity(eA, eB).also {
            Log.d(TAG, "crossSim=${"%.6f".format(it)}")
        }
    }

    // ── Lifecycle — releases model AND buffers ─────────────────────

    fun close() {
        inputBuffers  = null   // allows GC to release GPU-mapped memory
        outputBuffers = null
        model         = null
        activeAccelDesc = "none"
        Log.d(TAG, "ImageEmbedder closed")
    }
}