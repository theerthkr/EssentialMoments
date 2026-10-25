package com.theerthkr.essentialmoments.ml.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Loads an [OrtSession] for an ONNX model bundled in Android `assets/`.
 *
 * Because quantized foundation models (e.g. 91 MB vision + 271 MB text) are too large to load
 * via `ByteArray` on the Android JVM heap without risking `OutOfMemoryError`, this loader stages
 * the asset to `noBackupFilesDir/onnx_models/` once and initializes [OrtSession] from the file path
 * so ONNX Runtime can memory-map weights directly in native memory.
 */
internal object OnnxModelLoader {

    private const val MODEL_CACHE_DIR = "onnx_models"
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private val stageLock = Any()

    val environment: OrtEnvironment
        get() = OrtEnvironment.getEnvironment()

    fun createSessionFromAsset(
        context: Context,
        assetFileName: String,
        tag: String
    ): OrtSession {
        val stagedFile = stageAssetToFile(context, assetFileName, tag)
        return createSessionFromFile(stagedFile, tag)
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
                Log.d(tag, "Initialized OrtSession from ${modelFile.name} (${modelFile.length() / (1024 * 1024)} MB)")
            }
        }
    }

    private fun stageAssetToFile(
        context: Context,
        assetFileName: String,
        tag: String
    ): File {
        synchronized(stageLock) {
            val cacheDir = File(context.noBackupFilesDir, MODEL_CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val targetFile = File(cacheDir, assetFileName)
            if (targetFile.exists() && targetFile.length() > 0L) {
                return targetFile
            }

            val tempFile = File(cacheDir, "$assetFileName.tmp")
            Log.d(tag, "Staging ONNX asset $assetFileName to ${targetFile.absolutePath}...")
            context.assets.open(assetFileName).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            return targetFile
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
