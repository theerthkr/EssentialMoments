package com.theerthkr.essentialmoments.ml

import android.content.Context
import com.theerthkr.essentialmoments.ml.litert.LiteRtSiglipImageEmbedder
import com.theerthkr.essentialmoments.ml.litert.LiteRtSiglipTextEmbedder

/**
 * Single factory entry point for creating [ImageEmbedder] and [TextEmbedder] instances.
 *
 * Callers across the app depend only on the [ImageEmbedder] and [TextEmbedder] interfaces.
 * Switching or adding a new runtime backend (e.g. ONNX Runtime or user-downloaded models)
 * requires changing only this factory.
 */
object EmbeddingModelFactory {

    fun createImageEmbedder(context: Context): ImageEmbedder {
        return LiteRtSiglipImageEmbedder(context)
    }

    fun createTextEmbedder(context: Context): TextEmbedder {
        return LiteRtSiglipTextEmbedder(context)
    }
}
