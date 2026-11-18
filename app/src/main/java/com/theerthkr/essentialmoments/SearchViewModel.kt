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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for photo search.
 *
 * Interacts only with deep module interfaces:
 * - [PhotoIndexer.state] as single source of truth for indexing progress
 * - [IndexingScheduler] for triggering background index work
 * - [VectorIndex] for exact cosine similarity vector search over generated embeddings
 * - [InferenceArbiter] for prioritizing interactive text queries over background jobs
 */
class SearchViewModel(private val appContext: Context) : ViewModel() {

    private val app = appContext.applicationContext as App
    private val vectorIndex = app.vectorIndex
    private val photoIndexer = app.photoIndexer

    /** Single source of truth for indexing progress across the app. */
    val indexingState: StateFlow<IndexingState> = photoIndexer.state

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)

    /** Outcome of the last submitted search; typing alone never changes it. */
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private var searchJob: Job? = null

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun indexImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        IndexingScheduler.scheduleIndexUris(appContext, uris.map { it.toString() })
    }

    /** Updates the draft query. Does not search; clearing the text cancels any running search. */
    fun onQueryChanged(text: String) {
        _query.value = text
        if (text.isBlank()) {
            searchJob?.cancel()
            searchJob = null
            _searchState.value = SearchState.Idle
        }
    }

    /**
     * Executes semantic photo search for the current draft query.
     *
     * Uses [InferenceArbiter] to run the text embedder at high priority, cooperatively
     * preempting background photo embedding without canceling WorkManager jobs.
     * Uses exact cosine similarity vector scan via [VectorIndex.queryAll].
     *
     * A new submission cancels the previous one. State is published on the main thread
     * after `withContext` returns, which throws if this search was cancelled meanwhile,
     * so a superseded search can never overwrite a newer one.
     */
    fun submitSearch() {
        val query = _query.value.trim()
        if (query.isEmpty()) return
        // Embedding is blocking and can't be interrupted, so don't stack duplicate work.
        if (_searchState.value == SearchState.Searching(query)) return

        searchJob?.cancel()
        _searchState.value = SearchState.Searching(query)
        searchJob = viewModelScope.launch {
            _searchState.value = try {
                val results = withContext(Dispatchers.Default) { runSearch(query) }
                if (results.isEmpty()) SearchState.NoResults(query)
                else SearchState.Results(query, results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Search failed", e)
                SearchState.Error(query)
            }
        }
    }

    private suspend fun runSearch(query: String): List<SearchResult> {
        // High-priority text embedding: yields background indexing in-process
        val (textModelId, queryEmbedding) = InferenceArbiter.DEFAULT.runHighPriority {
            EmbeddingModelFactory.createTextEmbedder(appContext).use { textEmbedder ->
                textEmbedder.modelId to textEmbedder.getEmbedding(query)
            }
        }

        // Only compare against image embeddings produced by the same modelId,
        // so a text query from model A is never scored against image embeddings
        // from model B.
        return vectorIndex.queryAll(queryEmbedding, textModelId).map {
            SearchResult(uri = it.uri, score = it.score)
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(context.applicationContext) as T
        }
    }
}
