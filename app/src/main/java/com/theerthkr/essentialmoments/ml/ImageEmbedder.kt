package com.theerthkr.essentialmoments.ml

import android.graphics.Bitmap
import android.net.Uri

/**
 * Backend-agnostic contract for computing normalized image embeddings.
 *
 * Implementations encapsulate model-specific preprocessing (resizing, channel layout,
 * mean/std normalization), hardware runtime execution (e.g. LiteRT, ONNX Runtime),
 * and L2 normalization.
 */
interface ImageEmbedder : AutoCloseable {
    val modelId: String
    val embeddingDimension: Int

    /**
     * Decodes [uri] into a [Bitmap], computes its L2-normalized embedding,
     * and recycles the decoded [Bitmap]. Returns `null` if the URI cannot be decoded.
     */
    fun getEmbedding(uri: Uri): FloatArray?

    /**
     * Convenience method decoding [uriString] into an embedding.
     */
    fun getEmbedding(uriString: String): FloatArray? =
        try {
            getEmbedding(Uri.parse(uriString))
        } catch (_: Exception) {
            null
        }

    /**
     * Computes an L2-normalized embedding from [bitmap].
     */
    fun getEmbedding(bitmap: Bitmap, onResized: ((Bitmap) -> Unit)? = null): FloatArray
}
