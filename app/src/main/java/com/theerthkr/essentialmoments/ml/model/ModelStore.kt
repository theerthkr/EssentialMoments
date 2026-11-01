package com.theerthkr.essentialmoments.ml.model

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.theerthkr.essentialmoments.BuildConfig
import java.io.File

/**
 * Filesystem authority for downloaded model files.
 *
 * Layout (inside `noBackupFilesDir`, so models are never uploaded to cloud backup):
 *
 * ```
 * ml_models/
 *   <modelId>/<version>/<file>
 *   staging/<version>/<file>.part
 *   dev/<file>                 (debug-only bundled models)
 * ```
 *
 * A file only becomes visible to the inference layer once it has been fully
 * downloaded, SHA-256 verified, and marked ready in [markFileReady].
 */
class ModelStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = File(context.noBackupFilesDir, ROOT_DIR)

    fun installedManifest(): ModelManifest? {
        val json = prefs.getString(KEY_INSTALLED_MANIFEST, null) ?: return null
        return try {
            ModelManifest.parse(json, "")
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable installed manifest", e)
            null
        }
    }

    fun installedVersion(): Int = prefs.getInt(KEY_INSTALLED_VERSION, 0)

    fun isReady(role: ModelRole): Boolean {
        val manifest = installedManifest() ?: return devAssetFile(role) != null
        val spec = manifest.file(role) ?: return false
        return isFileReady(manifest, spec) || devAssetFile(role) != null
    }

    fun isFileReady(manifest: ModelManifest, spec: ModelFileSpec): Boolean {
        val flagged = prefs.getLong(roleKey(manifest.version, spec.role), -1L)
        if (flagged != spec.sizeBytes) return false
        val file = finalFile(manifest, spec)
        return file.exists() && file.length() == spec.sizeBytes
    }

    /** Returns the installed file for [role], or an opt-in debug asset, or null. */
    fun file(role: ModelRole): File? {
        val manifest = installedManifest()
        if (manifest != null) {
            val spec = manifest.file(role)
            if (spec != null && isFileReady(manifest, spec)) {
                return finalFile(manifest, spec)
            }
        }
        return devAssetFile(role)
    }

    fun finalFile(manifest: ModelManifest, spec: ModelFileSpec): File =
        File(installDir(manifest), spec.path)

    fun stagingFile(spec: ModelFileSpec, version: Int): File =
        File(File(root, "staging/$version"), "${spec.path}.part")

    fun installDir(manifest: ModelManifest): File =
        File(root, "${safeModelId(manifest.modelId)}/${manifest.version}")

    /**
     * Records that [spec] is fully verified. The first completed file of a new
     * version becomes the "installed manifest" so vision can unlock indexing
     * before the text model finishes downloading.
     */
    fun markFileReady(manifest: ModelManifest, spec: ModelFileSpec) {
        val currentVersion = installedVersion()
        val editor = prefs.edit()
        if (currentVersion != manifest.version) {
            editor.putString(KEY_INSTALLED_MANIFEST, manifest.toJson())
            editor.putInt(KEY_INSTALLED_VERSION, manifest.version)
        }
        editor.putLong(roleKey(manifest.version, spec.role), spec.sizeBytes)
        editor.apply()
        Log.d(TAG, "Marked ${spec.role.wireName} ready for model v${manifest.version}")
    }

    /** Removes every downloaded/bundled copy and all install bookkeeping. */
    fun deleteAll() {
        root.deleteRecursively()
        prefs.edit().clear().apply()
    }

    /** Deletes older model versions after a successful upgrade. */
    fun pruneOldVersions(modelId: String, keepVersion: Int) {
        val modelDir = File(root, safeModelId(modelId))
        modelDir.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.toIntOrNull() != keepVersion) {
                child.deleteRecursively()
            }
        }
    }

    fun installedBytes(): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun freeBytes(): Long = try {
        StatFs(context.noBackupFilesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        0L
    }

    /** Deletes the pre-download era staging directory from older installs. */
    fun deleteLegacyStaging() {
        File(context.noBackupFilesDir, "onnx_models").deleteRecursively()
    }

    private fun roleKey(version: Int, role: ModelRole) = "role_ready_${version}_${role.wireName}"

    private fun safeModelId(modelId: String) =
        modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * Debug-only path: when `-PbundleModels=true` is set, the models are in
     * `assets/onnx_int8/` and are staged once into `ml_models/dev/`.
     */
    private fun devAssetFile(role: ModelRole): File? {
        if (!BuildConfig.BUNDLE_MODELS) return null
        val assetPath = when (role) {
            ModelRole.VISION -> "onnx_int8/vision_model_int8.onnx"
            ModelRole.TEXT -> "onnx_int8/text_model_int8.onnx"
            ModelRole.VOCAB -> "onnx_int8/siglip2_vocab.bin"
        }
        val target = File(File(root, "dev"), assetPath.substringAfterLast('/'))
        if (target.exists() && target.length() > 0L) return target

        return try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            context.assets.open(assetPath).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Could not stage bundled debug model $assetPath", e)
            null
        }
    }

    companion object {
        private const val TAG = "ModelStore"
        private const val PREFS = "model_store"
        private const val ROOT_DIR = "ml_models"
        private const val KEY_INSTALLED_MANIFEST = "installed_manifest_json"
        private const val KEY_INSTALLED_VERSION = "installed_version"

        /** Extra headroom for staging and filesystem overhead. */
        const val STORAGE_FACTOR = 1.1
    }
}
