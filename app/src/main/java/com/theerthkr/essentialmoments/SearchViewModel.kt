package com.theerthkr.essentialmoments

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.theerthkr.essentialmoments.ml.EmbeddingStore
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.ml.IndexingWorker
import com.theerthkr.essentialmoments.ml.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val store    = EmbeddingStore(application)
    private val embedder = ImageEmbedder(application)
    private val textEmbedder = TextEmbedder(application)  // we'll build this next

    // ── Indexing progress ─────────────────────────────────────────
    private val _indexingProgress = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingProgress: StateFlow<IndexingState> = _indexingProgress.asStateFlow()

    // ── Search results ────────────────────────────────────────────
    private val _searchResults = MutableStateFlow<List<MediaImage>>(emptyList())
    val searchResults: StateFlow<List<MediaImage>> = _searchResults.asStateFlow()

    init {
        embedder.initialize()
        observeIndexingWork()
    }

    // ── Indexing ──────────────────────────────────────────────────

    fun startIndexing() {
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(
                IndexingWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,  // don't restart if already running
                request
            )
    }

    private fun observeIndexingWork() {
        WorkManager.getInstance(getApplication())
            .getWorkInfosForUniqueWorkLiveData(IndexingWorker.WORK_NAME)
        // Convert to StateFlow — simplified here, use observeForever in real code
    }

    // ── Search ────────────────────────────────────────────────────

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val queryEmbedding = textEmbedder.embed(query) ?: return@launch
            val hits = store.search(queryEmbedding, topK = 30)
            val images = hits.mapNotNull { resolveImage(it.imageId) }
            _searchResults.value = images
        }
    }

    private fun resolveImage(imageId: String): MediaImage? {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageId.toLong()
        )
        return MediaImage(imageId.toLong(), uri.toString(), "", 0L)
    }

    val indexedCount get() = store.indexedCount()

    override fun onCleared() {
        super.onCleared()
        embedder.close()
    }
}

sealed class IndexingState {
    object Idle : IndexingState()
    data class Running(val indexed: Int, val total: Int) : IndexingState()
    object Done : IndexingState()
}