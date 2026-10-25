package com.theerthkr.essentialmoments.ml

import android.content.Context
import com.theerthkr.essentialmoments.ml.litert.LiteRtSiglipImageEmbedder
import com.theerthkr.essentialmoments.ml.litert.LiteRtSiglipTextEmbedder
import com.theerthkr.essentialmoments.ml.onnx.OnnxSiglipImageEmbedder
import com.theerthkr.essentialmoments.ml.onnx.OnnxSiglipTextEmbedder

/**
 * Single factory entry point for creating [ImageEmbedder] and [TextEmbedder] instances.
 *
 * Callers across the app depend only on the [ImageEmbedder] and [TextEmbedder] interfaces.
 * Defaults to the INT8 quantized ONNX Runtime adapter ([Backend.ONNX_INT8]) while retaining
 * the LiteRT adapter ([Backend.LITERT]) behind the same seam.
 */
object EmbeddingModelFactory {

    enum class Backend {
        ONNX_INT8,
        LITERT
    }

    @Volatile
    var activeBackend: Backend = Backend.ONNX_INT8

    fun createImageEmbedder(
        context: Context,
        backend: Backend = activeBackend
    ): ImageEmbedder {
        return when (backend) {
            Backend.ONNX_INT8 -> OnnxSiglipImageEmbedder(context)
            Backend.LITERT -> LiteRtSiglipImageEmbedder(context)
        }
    }

    fun createTextEmbedder(
        context: Context,
        backend: Backend = activeBackend
    ): TextEmbedder {
        return when (backend) {
            Backend.ONNX_INT8 -> OnnxSiglipTextEmbedder(context)
            Backend.LITERT -> LiteRtSiglipTextEmbedder(context)
        }
    }
}
