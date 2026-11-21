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
import com.theerthkr.essentialmoments.permissions.AppPermissions
import com.theerthkr.essentialmoments.permissions.MediaAccess
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
    private val mediaCatalog = app.mediaCatalog

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
     * Re-applies the current photo-access rules to results that are already on
     * screen. Called when Search returns to the foreground, because a partial
     * selection can change in system settings without recreating the activity.
     */
    fun refreshVisibleResults() {
        viewModelScope.launch(Dispatchers.Default) {
            val current = _searchState.value as? SearchState.Results ?: return@launch
            when (AppPermissions.mediaAccess(appContext)) {
                MediaAccess.NONE -> _searchState.value = SearchState.Idle
                MediaAccess.PARTIAL -> {
                    val accessibleUris = withContext(Dispatchers.IO) {
                        mediaCatalog.allPhotoUris().toHashSet()
                    }
                    val visible = current.results.filter { it.uri in accessibleUris }
                    _searchState.value = if (visible.isEmpty()) {
                        SearchState.NoResults(current.query)
                    } else {
                        current.copy(results = visible)
                    }
                }
                MediaAccess.FULL -> Unit
            }
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

        // Without photo access there is nothing to search; clear any stale results.
        // The content area shows the photo-access hint instead of a result state.
        if (!AppPermissions.mediaAccess(appContext).canReadPhotos) {
            searchJob?.cancel()
            searchJob = null
            _searchState.value = SearchState.Idle
            return
        }

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
        val access = AppPermissions.mediaAccess(appContext)
        if (!access.canReadPhotos) return emptyList()

        // High-priority text embedding: yields background indexing in-process
        val (textModelId, queryEmbedding) = InferenceArbiter.DEFAULT.runHighPriority {
            EmbeddingModelFactory.createTextEmbedder(appContext).use { textEmbedder ->
                textEmbedder.modelId to textEmbedder.getEmbedding(query)
            }
        }

        // Only compare against image embeddings produced by the same modelId,
        // so a text query from model A is never scored against image embeddings
        // from model B.
        var scoredResults = vectorIndex.queryAll(queryEmbedding, textModelId)

        // Under partial access the user's selection can change while the
        // app is running. Never surface a photo that is no longer readable.
        if (access == MediaAccess.PARTIAL) {
            val accessibleUris = withContext(Dispatchers.IO) {
                mediaCatalog.allPhotoUris().toHashSet()
            }
            scoredResults = scoredResults.filter { it.uri in accessibleUris }
        }

        return scoredResults.map { SearchResult(uri = it.uri, score = it.score) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(context.applicationContext) as T
        }
    }
}
