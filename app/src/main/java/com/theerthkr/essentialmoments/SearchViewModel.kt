package com.theerthkr.essentialmoments

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.theerthkr.essentialmoments.ml.EmbeddingStore
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import com.theerthkr.essentialmoments.ml.IndexingWorker
import com.theerthkr.essentialmoments.ml.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "SearchViewModel"

@OptIn(FlowPreview::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val store       = EmbeddingStore(application)
    private val imageEmbedder = ImageEmbedder(application)
    private val textEmbedder  = TextEmbedder(application)

    // ── Indexing state ─────────────────────────────────────────────
    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    // ── Search query (raw input from TextField) ────────────────────
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // ── Search results ─────────────────────────────────────────────
    private val _searchResults = MutableStateFlow<List<MediaImage>>(emptyList())
    val searchResults: StateFlow<List<MediaImage>> = _searchResults.asStateFlow()

    // ── Search state ───────────────────────────────────────────────
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        // Initialize embedders on a background thread immediately
        viewModelScope.launch(Dispatchers.Default) {
            imageEmbedder.initialize()
            textEmbedder.initialize()
            Log.d(TAG, "Embedders ready. Indexed images: ${store.indexedCount()}")
        }

        // Observe live WorkManager state for IndexingWorker
        observeIndexingWork()

        // Debounce the query — wait 400ms after user stops typing before searching
        // This prevents a model inference call on every single keystroke
        viewModelScope.launch {
            _query
                .debounce(400L)
                .distinctUntilChanged()
                .collect { q -> executeSearch(q) }
        }
    }

    // ── Public API ─────────────────────────────────────────────────

    /** Called from SearchActivity on every TextField change */
    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        if (newQuery.isBlank()) {
            _searchResults.value = emptyList()
        }
    }

    /** Enqueues the IndexingWorker — safe to call multiple times (KEEP policy) */
    fun startIndexing() {
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(false)   // allow even on low battery during debug
                    .build()
            )
            .build()

        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(
                IndexingWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )

        Log.d(TAG, "IndexingWorker enqueued")
    }

    val indexedCount get() = store.indexedCount()

    // ── Search execution ───────────────────────────────────────────

    private fun executeSearch(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        if (store.indexedCount() == 0) {
            Log.w(TAG, "Search attempted but store is empty — start indexing first")
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _isSearching.value = true
            Log.d(TAG, "Searching for: '$query'  (store size=${store.indexedCount()})")

            val queryEmbedding = textEmbedder.embed(query)
            if (queryEmbedding == null) {
                Log.e(TAG, "Text embedding failed for query='$query'")
                _isSearching.value = false
                return@launch
            }

            val hits   = store.search(queryEmbedding, topK = 30)
            val images = hits.mapNotNull { resolveImage(it.imageId) }

            Log.d(TAG, "Search '$query' → ${hits.size} hits, resolved ${images.size} images")
            if (hits.isNotEmpty()) {
                Log.d(TAG, "Top score: ${"%.4f".format(hits.first().score)}  " +
                        "bottom: ${"%.4f".format(hits.last().score)}")
            }

            _searchResults.value = images
            _isSearching.value   = false
        }
    }

    private fun resolveImage(imageId: String): MediaImage? {
        return try {
            val id  = imageId.toLong()
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            )
            MediaImage(id, uri.toString(), "", 0L)
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Invalid imageId: $imageId")
            null
        }
    }

    // ── WorkManager observation ────────────────────────────────────

    private fun observeIndexingWork() {
        WorkManager.getInstance(getApplication())
            .getWorkInfosForUniqueWorkFlow(IndexingWorker.WORK_NAME)
            .onEach { workInfoList ->
                val info = workInfoList.firstOrNull()
                _indexingState.value = when (info?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> IndexingState.Queued
                    WorkInfo.State.RUNNING -> {
                        val indexed = info.progress.getInt(IndexingWorker.KEY_INDEXED, 0)
                        val total   = info.progress.getInt(IndexingWorker.KEY_TOTAL,   0)
                        val failed  = info.progress.getInt(IndexingWorker.KEY_FAILED,  0)
                        IndexingState.Running(indexed, total, failed)
                    }
                    WorkInfo.State.SUCCEEDED -> IndexingState.Done(store.indexedCount())
                    WorkInfo.State.FAILED    -> IndexingState.Failed
                    WorkInfo.State.CANCELLED -> IndexingState.Idle
                    null -> {
                        // No work ever enqueued — check if we already have a store
                        if (store.indexedCount() > 0)
                            IndexingState.Done(store.indexedCount())
                        else
                            IndexingState.Idle
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        imageEmbedder.close()
        textEmbedder.close()
    }
}

// ── Indexing state ADT ─────────────────────────────────────────────

sealed class IndexingState {
    object Idle    : IndexingState()
    object Queued  : IndexingState()
    object Failed  : IndexingState()
    data class Running(val indexed: Int, val total: Int, val failed: Int) : IndexingState()
    data class Done(val total: Int) : IndexingState()
}