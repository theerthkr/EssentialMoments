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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchResult(val uri: String, val score: Float)

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
     * Re-applies the current photo-access rules to results that are already on
     * screen. Called when Search returns to the foreground, because a partial
     * selection can change in system settings without recreating the activity.
     */
    fun refreshVisibleResults() {
        viewModelScope.launch(Dispatchers.Default) {
            when (AppPermissions.mediaAccess(appContext)) {
                MediaAccess.NONE -> _searchResults.value = emptyList()
                MediaAccess.PARTIAL -> {
                    val accessibleUris = withContext(Dispatchers.IO) {
                        mediaCatalog.allPhotoUris().toHashSet()
                    }
                    _searchResults.value = _searchResults.value.filter {
                        it.uri in accessibleUris
                    }
                }
                MediaAccess.FULL -> Unit
            }
        }
    }

    /**
     * Executes semantic photo search.
     *
     * Uses [InferenceArbiter] to run the text embedder at high priority, cooperatively
     * preempting background photo embedding without canceling WorkManager jobs.
     * Uses exact cosine similarity vector scan via [VectorIndex.queryAll].
     */
    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) {
            _isSearching.value = true
            try {
                val access = AppPermissions.mediaAccess(appContext)
                if (!access.canReadPhotos) {
                    _searchResults.value = emptyList()
                    return@launch
                }

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
