package com.theerthkr.essentialmoments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Converts an image URI into a Bitmap so the AI model can process it.
 */
fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
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
    val magnitude = kotlin.math.sqrt(sumOfSquares)
    if (magnitude == 0.0f) return embedding

    val normalized = FloatArray(embedding.size)
    for (i in embedding.indices) {
        normalized[i] = embedding[i] / magnitude
    }
    return normalized
}