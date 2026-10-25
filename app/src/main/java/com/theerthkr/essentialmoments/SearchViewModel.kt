package com.theerthkr.essentialmoments

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theerthkr.essentialmoments.indexing.IndexingScheduler
import com.theerthkr.essentialmoments.indexing.IndexingState
import com.theerthkr.essentialmoments.ml.EmbeddingModelFactory
import com.theerthkr.essentialmoments.ml.InferenceArbiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchResult(val uri: String, val score: Float)

/**
 * ViewModel for photo search.
 *
 * Interacts only with deep module interfaces:
 * - [PhotoIndexer.state] as single source of truth for indexing progress
 * - [IndexingScheduler] for triggering background index work
 * - [VectorIndex] for HNSW approximate nearest-neighbor search (top-500) and exact scan
 * - [InferenceArbiter] for prioritizing interactive text queries over background jobs
 */
class SearchViewModel(private val appContext: Context) : ViewModel() {

    private val app = appContext.applicationContext as App
    private val vectorIndex = app.vectorIndex
    private val photoIndexer = app.photoIndexer

    /** Single source of truth for indexing progress across the app. */
    val indexingState: StateFlow<IndexingState> = photoIndexer.state

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun indexImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        IndexingScheduler.scheduleIndexUris(appContext, uris.map { it.toString() })
    }

    fun onQueryChanged(text: String) {
        _query.value = text
        if (text.isBlank()) {
            _searchResults.value = emptyList()
        }
    }

    /**
     * Executes semantic photo search.
     *
     * Uses [InferenceArbiter] to run the text embedder at high priority, cooperatively
     * preempting background photo embedding without canceling WorkManager jobs.
     * Uses HNSW vector search via [VectorIndex.queryTopK] (default 500 candidates).
     */
    fun search(query: String, useExactScan: Boolean = false, topK: Int = 500) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) {
            _isSearching.value = true
            try {
                // High-priority text embedding: yields background indexing in-process
                val queryEmbedding = InferenceArbiter.DEFAULT.runHighPriority {
                    EmbeddingModelFactory.createTextEmbedder(appContext).use { textEmbedder ->
                        textEmbedder.getEmbedding(query)
                    }
                }

                val scoredResults = if (useExactScan) {
                    vectorIndex.queryAll(queryEmbedding)
                } else {
                    vectorIndex.queryTopK(queryEmbedding, topK = topK)
                }

                _searchResults.value = scoredResults.map {
                    SearchResult(uri = it.uri, score = it.score)
                }
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Search error for query: $query", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(context.applicationContext) as T
        }
    }
}
