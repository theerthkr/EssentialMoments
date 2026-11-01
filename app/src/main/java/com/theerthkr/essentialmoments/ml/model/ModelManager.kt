package com.theerthkr.essentialmoments.ml.model

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.theerthkr.essentialmoments.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Live download progress for the download screen and notifications. */
data class DownloadProgress(
    val role: ModelRole,
    val fileName: String,
    val bytes: Long,
    val totalBytes: Long,
    val overallBytes: Long,
    val overallTotalBytes: Long,
    val speedBytesPerSecond: Long
) {
    val fraction: Float
        get() = if (overallTotalBytes > 0L) {
            (overallBytes.toDouble() / overallTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else 0f
}

data class ModelStatus(
    val manifest: ModelManifest? = null,
    val installedManifest: ModelManifest? = null,
    val readyRoles: Set<ModelRole> = emptySet(),
    val checking: Boolean = false,
    val queued: Boolean = false,
    val download: DownloadProgress? = null,
    val error: String? = null
) {
    val visionReady: Boolean get() = ModelRole.VISION in readyRoles
    val textReady: Boolean get() = ModelRole.TEXT in readyRoles && ModelRole.VOCAB in readyRoles
    val fullyReady: Boolean get() = visionReady && textReady
    val installedVersion: Int get() = installedManifest?.version ?: 0
    val availableVersion: Int? get() = manifest?.version
    val updateAvailable: Boolean
        get() = installedVersion > 0 && manifest != null && manifest.version > installedVersion
    val totalBytes: Long get() = manifest?.totalBytes ?: 0L
}

/**
 * Single source of truth for model availability, downloads, and installation.
 *
 * UI observes [state]; inference code asks [file] / [isReady]. The actual
 * transfer runs in [ModelDownloadWorker] so it survives the UI and is retried
 * by WorkManager.
 */
class ModelManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val store = ModelStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ModelStatus())
    val state: StateFlow<ModelStatus> = _state.asStateFlow()

    init {
        store.deleteLegacyStaging()
        _state.value = buildStatus(
            manifest = readCachedManifest(),
            error = null
        )
    }

    fun file(role: ModelRole) = store.file(role)

    fun isReady(role: ModelRole) = store.isReady(role)

    fun isVisionReady() = store.isReady(ModelRole.VISION)

    fun isTextReady() = store.isReady(ModelRole.TEXT) && store.isReady(ModelRole.VOCAB)

    fun isFullyReady() = isVisionReady() && isTextReady()

    fun installedBytes(): Long = store.installedBytes()

    fun freeBytes(): Long = store.freeBytes()

    fun isWifiOnly(): Boolean = prefs.getBoolean(KEY_WIFI_ONLY, false)

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
    }

    /** Re-reads installed state without hitting the network. */
    fun refreshInstalled() {
        _state.update { current -> buildStatus(manifest = current.manifest, error = current.error) }
    }

    /** Fetches and verifies the remote manifest. Safe to call repeatedly. */
    suspend fun fetchManifest(): ModelManifest? = withContext(Dispatchers.IO) {
        _state.update { it.copy(checking = true, error = null) }
        try {
            val manifestUrl = BuildConfig.MODEL_MANIFEST_URL
            val bytes = fetchBytes(manifestUrl)
            if (BuildConfig.MODEL_MANIFEST_PUBLIC_KEY.isNotBlank()) {
                val signature = fetchBytes("$manifestUrl.sig")
                if (!ModelManifestVerifier.verify(
                        bytes,
                        signature,
                        BuildConfig.MODEL_MANIFEST_PUBLIC_KEY
                    )
                ) {
                    throw IOException("Model manifest signature verification failed")
                }
            }
            val manifest = ModelManifest.parse(bytes.toString(Charsets.UTF_8), manifestUrl)
            prefs.edit().putString(KEY_CACHED_MANIFEST, manifest.toJson()).apply()
            _state.update {
                it.copy(manifest = manifest, checking = false, error = null)
            }
            manifest
        } catch (e: Exception) {
            Log.e(TAG, "Could not fetch model manifest", e)
            val hasInstalledModels = isVisionReady()
            _state.update {
                it.copy(
                    checking = false,
                    // Offline with models already installed is not an error; the
                    // app can keep using what it has.
                    error = if (hasInstalledModels) null
                    else "Could not check for models: ${e.readableMessage()}"
                )
            }
            null
        }
    }

    /**
     * Starts (or resumes) the model download. Fetches the manifest first when
     * the app has not seen one yet, then enqueues foreground WorkManager work.
     */
    fun startDownload() {
        scope.launch {
            val manifest = _state.value.manifest ?: fetchManifest()
            if (manifest == null) return@launch

            val required = (manifest.totalBytes * ModelStore.STORAGE_FACTOR).toLong()
            val free = store.freeBytes()
            if (free in 1 until required) {
                _state.update {
                    it.copy(
                        error = "Not enough storage. Need ${required.asMb()} MB free, " +
                            "but only ${free.asMb()} MB is available."
                    )
                }
                return@launch
            }

            enqueueDownload(manifest)
        }
    }

    private fun enqueueDownload(manifest: ModelManifest) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (isWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MANIFEST to manifest.toJson()))
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        _state.update { it.copy(queued = true, download = null, error = null) }
    }

    fun pauseDownload() {
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        _state.update { it.copy(queued = false, download = null) }
    }

    fun deleteModels() {
        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        com.theerthkr.essentialmoments.indexing.IndexingScheduler.cancel(appContext)
        store.deleteAll()
        prefs.edit().remove(KEY_CACHED_MANIFEST).apply()
        _state.value = ModelStatus(manifest = _state.value.manifest)
    }

    // ── Worker callbacks ────────────────────────────────────────────────────

    fun onDownloadStarted(manifest: ModelManifest) {
        _state.value = buildStatus(manifest = manifest, queued = true, download = null, error = null)
    }

    fun onProgress(progress: DownloadProgress) {
        _state.update { it.copy(queued = true, download = progress, error = null) }
    }

    fun onFileReady(manifest: ModelManifest, spec: ModelFileSpec) {
        store.markFileReady(manifest, spec)
        _state.value = buildStatus(manifest = manifest, queued = true)
        if (spec.role == ModelRole.VISION) {
            // Start building the photo index as soon as the (much smaller)
            // vision model is ready, while the text model keeps downloading.
            com.theerthkr.essentialmoments.indexing.IndexingScheduler
                .scheduleIndexAll(appContext)
        }
    }

    fun onDownloadComplete(manifest: ModelManifest) {
        store.pruneOldVersions(manifest.modelId, manifest.version)
        fetchCachedManifestOrKeep(manifest)
        _state.value = buildStatus(manifest = manifest, queued = false)
        com.theerthkr.essentialmoments.indexing.IndexingScheduler
            .scheduleIndexAll(appContext)
    }

    fun onDownloadError(message: String, retrying: Boolean) {
        _state.update {
            it.copy(
                queued = retrying,
                download = null,
                error = message
            )
        }
    }

    fun onDownloadCancelled() {
        _state.update { it.copy(queued = false, download = null) }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun buildStatus(
        manifest: ModelManifest?,
        queued: Boolean = false,
        download: DownloadProgress? = null,
        error: String? = null
    ): ModelStatus {
        val installed = store.installedManifest()
        val roles = buildSet {
            ModelRole.entries.forEach { role ->
                if (store.isReady(role)) add(role)
            }
        }
        return ModelStatus(
            manifest = manifest,
            installedManifest = installed,
            readyRoles = roles,
            queued = queued,
            download = download,
            error = error
        )
    }

    private fun readCachedManifest(): ModelManifest? {
        val json = prefs.getString(KEY_CACHED_MANIFEST, null) ?: return null
        return try {
            ModelManifest.parse(json, "")
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring corrupt cached manifest", e)
            null
        }
    }

    private fun fetchCachedManifestOrKeep(manifest: ModelManifest) {
        prefs.edit().putString(KEY_CACHED_MANIFEST, manifest.toJson()).apply()
    }

    fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.bytes() ?: throw IOException("Empty response from $url")
        }
    }

    companion object {
        private const val TAG = "ModelManager"
        internal const val UNIQUE_WORK_NAME = "model_download"
        private const val PREFS = "model_manager"
        private const val KEY_CACHED_MANIFEST = "cached_manifest_json"
        private const val KEY_WIFI_ONLY = "wifi_only"
    }
}

private fun Long.asMb(): Long = (this / (1024 * 1024)).coerceAtLeast(0)

private fun Throwable.readableMessage(): String {
    var root: Throwable = this
    var cause: Throwable? = this.cause
    while (cause != null) {
        root = cause
        cause = cause.cause
    }
    return root.message ?: this::class.java.simpleName
}
