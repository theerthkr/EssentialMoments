package com.theerthkr.essentialmoments.ml

/**
 * Backend-agnostic contract for computing normalized text query embeddings.
 *
 * Implementations encapsulate model-specific tokenization, padding,
 * hardware runtime execution (ONNX Runtime), and L2 normalization.
 */
interface TextEmbedder : AutoCloseable {
    val modelId: String
    val embeddingDimension: Int

    /**
     * Computes an L2-normalized embedding from [text].
     */
    fun getEmbedding(text: String): FloatArray
}
