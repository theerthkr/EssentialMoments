package com.theerthkr.essentialmoments.ml

import android.content.Context
import com.theerthkr.essentialmoments.ml.model.ModelRole
import com.theerthkr.essentialmoments.ml.model.ModelStore
import com.theerthkr.essentialmoments.ml.onnx.OnnxSiglipImageEmbedder
import com.theerthkr.essentialmoments.ml.onnx.OnnxSiglipTextEmbedder

/**
 * Single factory entry point for creating [ImageEmbedder] and [TextEmbedder] instances.
 *
 * Callers across the app depend only on the [ImageEmbedder] and [TextEmbedder] interfaces,
 * so the concrete ONNX Runtime implementation can be replaced without touching call sites.
 */
object EmbeddingModelFactory {

    fun createImageEmbedder(context: Context): ImageEmbedder {
        requireModel(context, ModelRole.VISION)
        return OnnxSiglipImageEmbedder(context)
    }

    fun createTextEmbedder(context: Context): TextEmbedder {
        requireModel(context, ModelRole.TEXT)
        requireModel(context, ModelRole.VOCAB)
        return OnnxSiglipTextEmbedder(context)
    }

    fun isVisionReady(context: Context): Boolean =
        ModelStore(context.applicationContext).isReady(ModelRole.VISION)

    fun isTextReady(context: Context): Boolean {
        val store = ModelStore(context.applicationContext)
        return store.isReady(ModelRole.TEXT) && store.isReady(ModelRole.VOCAB)
    }

    private fun requireModel(context: Context, role: ModelRole) {
        if (!ModelStore(context.applicationContext).isReady(role)) {
            throw ModelNotReadyException(role)
        }
    }
}
