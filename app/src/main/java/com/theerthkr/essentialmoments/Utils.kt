package com.theerthkr.essentialmoments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.sqrt

/**
 * Converts an image URI into a Bitmap so the AI model can process it.
 *
 * The decode is bounded to [maxDimension] (default 1024 px on the longest side) so a
 * 48 MP photo never allocates its full-resolution heap bitmap before being downscaled
 * to the model's 224×224 input.
 */
fun uriToBitmap(context: Context, uri: Uri, maxDimension: Int = 1024): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                // Downscale while decoding instead of allocating the full image first.
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > maxDimension && maxDimension > 0) {
                    val scale = maxDimension.toFloat() / maxSide
                    val targetW = maxOf(1, (info.size.width * scale).toInt())
                    val targetH = maxOf(1, (info.size.height * scale).toInt())
                    decoder.setTargetSize(targetW, targetH)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Normalizes the embedding vector so it can be used for semantic search[cite: 2].
 */
fun l2Normalize(embedding: FloatArray): FloatArray {
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
