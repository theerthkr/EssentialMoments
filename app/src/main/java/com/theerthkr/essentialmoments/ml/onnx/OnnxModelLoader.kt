package com.theerthkr.essentialmoments.ml.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.theerthkr.essentialmoments.ml.ModelNotReadyException
import com.theerthkr.essentialmoments.ml.model.ModelRole
import com.theerthkr.essentialmoments.ml.model.ModelStore
import java.io.File

/**
 * Loads an [OrtSession] for an ONNX model managed by [ModelStore].
 *
 * Models are no longer shipped inside the APK. They are downloaded by
 * `ModelDownloadWorker`, verified, and stored in `noBackupFilesDir/ml_models/`.
 * Loading directly from the file lets ONNX Runtime memory-map quantized weights
 * instead of copying hundreds of megabytes onto the Android JVM heap.
 */
internal object OnnxModelLoader {

    val environment: OrtEnvironment
        get() = OrtEnvironment.getEnvironment()

    /**
     * Opens an ONNX session for [role]. Throws [ModelNotReadyException] when the
     * file has not been downloaded yet.
     */
    fun createSessionFromStore(
        context: Context,
        role: ModelRole,
        tag: String
    ): OrtSession {
        val modelFile = ModelStore(context).file(role)
            ?: throw ModelNotReadyException(role)
        return try {
            createSessionFromFile(modelFile, tag)
        } catch (e: Exception) {
            Log.e(tag, "Failed to load ${modelFile.name}; deleting the corrupted file", e)
            modelFile.delete()
            throw IllegalStateException(
                "${role.displayName} model file was corrupted. Re-download the model in Settings.",
                e
            )
        }
    }

    fun createSessionFromFile(
        modelFile: File,
        tag: String
    ): OrtSession {
        val env = environment
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            setIntraOpNumThreads(cores)
        }
        return options.use { sessionOptions ->
            env.createSession(modelFile.absolutePath, sessionOptions).also {
                Log.d(
                    tag,
                    "Initialized OrtSession from ${modelFile.name} " +
                        "(${modelFile.length() / (1024 * 1024)} MB)"
                )
            }
        }
    }

    /**
     * Reads [expectedDim] floats from the first output tensor of [result].
     */
    fun extractFloatOutput(result: OrtSession.Result, expectedDim: Int): FloatArray {
        val tensor = result.get(0) as? OnnxTensor
            ?: throw IllegalStateException("Expected OnnxTensor output, got ${result.get(0)?.javaClass}")
        val floatBuffer = tensor.floatBuffer
            ?: throw IllegalStateException("Expected float32 output tensor")
        require(floatBuffer.remaining() == expectedDim) {
            "Expected $expectedDim floats in pooler_output, got ${floatBuffer.remaining()}"
        }
        val output = FloatArray(expectedDim)
        floatBuffer.get(output)
        return output
    }
}
