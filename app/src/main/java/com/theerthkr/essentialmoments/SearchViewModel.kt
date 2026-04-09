package com.theerthkr.essentialmoments

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theerthkr.essentialmoments.ml.EmbeddingStore
import com.theerthkr.essentialmoments.ml.IndexingWorker
import com.theerthkr.essentialmoments.ml.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


private const val TAG = "SearchViewModel"

@OptIn(FlowPreview::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val store        = EmbeddingStore(application)
    private val textEmbedder = TextEmbedder(application)

    private val _indexingState  = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    private val _query          = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults  = MutableStateFlow<List<MediaImage>>(emptyList())
    val searchResults: StateFlow<List<MediaImage>> = _searchResults.asStateFlow()

    private val _isSearching    = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            textEmbedder.initialize()
            Log.d(TAG, "TextEmbedder ready. Indexed: ${store.indexedCount()}")
        }

        observeIndexingWork()

        viewModelScope.launch {
            _query
                .debounce(400L)
                .distinctUntilChanged()
                .collect { executeSearch(it) }
        }
    }

    // ── Public API ─────────────────────────────────────────────────

    fun onQueryChanged(q: String) {
        _query.value = q
        if (q.isBlank()) _searchResults.value = emptyList()
    }

    fun startIndexing() = IndexingWorker.enqueue(getApplication())

    val indexedCount get() = store.indexedCount()

    // ── Search ─────────────────────────────────────────────────────

    private fun executeSearch(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        if (store.indexedCount() == 0) {
            Log.w(TAG, "Store empty"); _searchResults.value = emptyList(); return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _isSearching.value = true
            val qEmbed = textEmbedder.embed(query)
            if (qEmbed == null) { _isSearching.value = false; return@launch }

            val hits   = store.search(qEmbed, topK = 30)
            val images = hits.mapNotNull { resolveImage(it.imageId) }
            Log.d(TAG, "Search '$query' → ${images.size} results  " +
                    "top=${"%.3f".format(hits.firstOrNull()?.score ?: 0f)}")
            _searchResults.value = images
            _isSearching.value   = false
        }
    }

    private fun resolveImage(imageId: String): MediaImage? = try {
        val id  = imageId.toLong()
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        MediaImage(id, uri.toString(), "", 0L)
    } catch (e: NumberFormatException) { null }

    // ── WorkManager observation ────────────────────────────────────

    private fun observeIndexingWork() {
        WorkManager.getInstance(getApplication())
            .getWorkInfosForUniqueWorkFlow(IndexingWorker.WORK_NAME)
            .onEach { list ->
                val info = list.firstOrNull()
                _indexingState.value = when (info?.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED  -> IndexingState.Queued

                    WorkInfo.State.RUNNING  -> {
                        val indexed = info.progress.getInt(IndexingWorker.KEY_INDEXED, store.indexedCount())
                        val total   = info.progress.getInt(IndexingWorker.KEY_TOTAL,   0)
                        val failed  = info.progress.getInt(IndexingWorker.KEY_FAILED,  0)
                        IndexingState.Running(indexed, total, failed)
                    }

                    // SUCCEEDED means one run finished — may auto re-enqueue for more
                    WorkInfo.State.SUCCEEDED -> {
                        val done = store.indexedCount()
                        // Query total image count to see if more remain
                        val total = queryTotalImageCount()
                        if (done >= total) IndexingState.Done(done)
                        else IndexingState.Running(done, total, 0) // next run enqueued
                    }

                    WorkInfo.State.FAILED    -> IndexingState.Failed
                    WorkInfo.State.CANCELLED -> IndexingState.Idle
                    null -> if (store.indexedCount() > 0)
                        IndexingState.Done(store.indexedCount())
                    else IndexingState.Idle
                }
            }
            .launchIn(viewModelScope)
    }

    private fun queryTotalImageCount(): Int {
        return try {
            getApplication<Application>().contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null, null
            )?.use { it.count } ?: 0
        } catch (e: Exception) { 0 }
    }

    override fun onCleared() {
        super.onCleared()
        textEmbedder.close()
    }
}

sealed class IndexingState {
    object Idle   : IndexingState()
    object Queued : IndexingState()
    object Failed : IndexingState()
    data class Running(val indexed: Int, val total: Int, val failed: Int) : IndexingState()
    data class Done(val total: Int) : IndexingState()
}