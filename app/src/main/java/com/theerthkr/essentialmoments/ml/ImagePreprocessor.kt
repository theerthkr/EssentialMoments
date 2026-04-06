package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a device image URI into a ByteBuffer ready for SigLIP2 inference.
 *
 * SigLIP2 contract:
 *   - Input shape  : [1, 224, 224, 3]  (NHWC)
 *   - Dtype        : float16 (2 bytes per value)
 *   - Pixel range  : [-1.0, 1.0]
 *   - Normalisation: (pixel_uint8 / 127.5) - 1.0
 *   - Buffer size  : 1 × 224 × 224 × 3 × 2 = 301,056 bytes
 */
class ImagePreprocessor(private val context: Context) {

    companion object {
        private const val TAG = "ImagePreprocessor"
        const val INPUT_SIZE = 224
        const val CHANNELS   = 3
        // float16 = 2 bytes; total = 1 × 224 × 224 × 3 × 2
        const val BUFFER_BYTES = INPUT_SIZE * INPUT_SIZE * CHANNELS * 2
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Main entry-point used by ImageEmbedder.
     * Returns null if the URI cannot be decoded.
     */
    fun preprocess(uri: Uri, debug: Boolean = false): ByteBuffer? {
        val raw = loadBitmap(uri) ?: return null
        return preprocessBitmap(raw, debug)
    }

    fun preprocessBitmap(bitmap: Bitmap, debug: Boolean = false): ByteBuffer {
        val cropped = centerCrop(bitmap, debug)
        return packFloat16(cropped, debug)
    }

    // ─────────────────────────────────────────────────────────────
    // Step 1 – Load
    // ─────────────────────────────────────────────────────────────

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?.let { bmp ->
                        // Ensure ARGB_8888 — some camera outputs are RGB_565
                        if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                        else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmap failed for $uri : ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Step 2 – Center-crop to 224 × 224
    // ─────────────────────────────────────────────────────────────

    fun centerCrop(src: Bitmap, debug: Boolean = false): Bitmap {
        val side  = minOf(src.width, src.height)
        val left  = (src.width  - side) / 2
        val top   = (src.height - side) / 2

        val square  = Bitmap.createBitmap(src, left, top, side, side)
        val resized = Bitmap.createScaledBitmap(square, INPUT_SIZE, INPUT_SIZE, true)
        if (square !== src) square.recycle()

        if (debug) {
            Log.d(TAG, "centerCrop: ${src.width}×${src.height} → square($side) → ${INPUT_SIZE}×${INPUT_SIZE}")
            // Uncomment to save for visual inspection:
            // savePng(resized, "debug_crop_${System.currentTimeMillis()}.png")
        }
        return resized
    }

    // ─────────────────────────────────────────────────────────────
    // Step 3 – Normalise + pack as float16
    // ─────────────────────────────────────────────────────────────

    fun packFloat16(bitmap: Bitmap, debug: Boolean = false): ByteBuffer {
        require(bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            "Expected ${INPUT_SIZE}×${INPUT_SIZE}, got ${bitmap.width}×${bitmap.height}"
        }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val buf = ByteBuffer
            .allocateDirect(BUFFER_BYTES)
            .order(ByteOrder.nativeOrder())

        var minV =  Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE

        for (px in pixels) {
            val r = (px ushr 16 and 0xFF) / 127.5f - 1f
            val g = (px ushr  8 and 0xFF) / 127.5f - 1f
            val b = (px         and 0xFF) / 127.5f - 1f

            buf.putShort(f32ToF16(r))
            buf.putShort(f32ToF16(g))
            buf.putShort(f32ToF16(b))

            if (debug) { minV = minOf(minV, r, g, b); maxV = maxOf(maxV, r, g, b) }
        }

        buf.rewind()

        if (debug) {
            Log.d(TAG, "packFloat16: ${buf.capacity()} bytes (expected $BUFFER_BYTES)")
            Log.d(TAG, "  pixel range min=${"%.4f".format(minV)} max=${"%.4f".format(maxV)} → expect ≈[-1,1]")
        }
        return buf
    }

    // ─────────────────────────────────────────────────────────────
    // IEEE 754 float32 → float16 conversion
    // ─────────────────────────────────────────────────────────────

    private fun f32ToF16(v: Float): Short {
        val b = java.lang.Float.floatToRawIntBits(v)
        val sign  = (b ushr 31) and 0x1
        val exp32 = (b ushr 23) and 0xFF
        val frac  =  b          and 0x7FFFFF
        val exp16 = exp32 - 127 + 15
        return when {
            exp16 <= 0  -> (sign shl 15).toShort()
            exp16 >= 31 -> ((sign shl 15) or 0x7C00).toShort()
            else        -> ((sign shl 15) or (exp16 shl 10) or (frac ushr 13)).toShort()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Debug helpers
    // ─────────────────────────────────────────────────────────────

    /** Saves a bitmap to external files dir for visual inspection in Android Studio Device Explorer */
    fun savePng(bitmap: Bitmap, filename: String) {
        try {
            val f = java.io.File(context.getExternalFilesDir(null), filename)
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "Saved → ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "savePng failed: ${e.message}")
        }
    }
}