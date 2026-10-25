package com.theerthkr.essentialmoments.ml.litert

import android.content.res.AssetManager
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

/**
 * Loads a LiteRT [CompiledModel] from [assets] using hardware accelerator fallback (NPU -> GPU -> CPU).
 */
internal fun createCompiledModelWithFallback(
    assets: AssetManager,
    modelPath: String,
    tag: String
): CompiledModel {
    val accelerators = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
    for (accel in accelerators) {
        try {
            return CompiledModel.create(
                assets,
                modelPath,
                CompiledModel.Options(accel)
            ).also {
                Log.d(tag, "Loaded $modelPath on $accel")
            }
        } catch (e: Exception) {
            Log.w(tag, "$accel unavailable for $modelPath: ${e.message}")
        }
    }
    throw IllegalStateException("Failed to initialize LiteRT model $modelPath on any accelerator (NPU, GPU, CPU)")
}
