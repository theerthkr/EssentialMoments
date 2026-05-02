package com.theerthkr.essentialmoments

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


// --- Data Models ---


data class SearchResult(
    val uri: String,
    val score: Float
)


sealed class IndexingState {
    object Idle : IndexingState()
    data class Indexing(val current: Int, val total: Int) : IndexingState()
    data class Done(val count: Int) : IndexingState()
    data class Error(val message: String) : IndexingState()
}

// --- ViewModel ---

class SearchViewModel(private val appContext: Context) : ViewModel() {

    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Single source of truth: ObjectBox
    private val imageBox = ObjectBox.store.boxFor(ImageEntity::class.java)

    init {
        // Observe background worker status to update the UI banner[cite: 9]
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkLiveData("image_indexing_task")
                .asFlow()
                .collect { infoList ->
                    val workInfo = infoList.firstOrNull()
                    _indexingState.value = when (workInfo?.state) {
                        WorkInfo.State.RUNNING -> {
                            val current = workInfo.progress.getInt("PROGRESS", 0)
                            val total = workInfo.progress.getInt("TOTAL", 0)
                            IndexingState.Indexing(current, total)
                        }
                        WorkInfo.State.SUCCEEDED -> IndexingState.Done(0)
                        else -> IndexingState.Idle
                    }
                }
        }
    }

    fun onQueryChanged(text: String) {
        _query.value = text
        if (text.isBlank()) _searchResults.value = emptyList()
    }

    fun search(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch(Dispatchers.Default) {
            // PAUSE indexing to free up NPU/CPU for search inference[cite: 9]
            WorkManager.getInstance(appContext).cancelUniqueWork("image_indexing_task")

            _isSearching.value = true
            try {
                // Generate Text Embedding[cite: 2]
                val textProcessor = SiglipTextProcessor(appContext)
                val queryEmbedding = textProcessor.getEmbedding(query)
                textProcessor.close()

                // Perform Similarity Search against ObjectBox data[cite: 9]
                val allImages = imageBox.all
                val scored = allImages.map { entity ->
                    val score = cosineSimilarity(queryEmbedding, entity.embedding)
                    SearchResult(uri = entity.uri, score = score)
                }.sortedByDescending { it.score }

                _searchResults.value = scored
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Search Error", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    // Factory to ensure Context is passed correctly[cite: 9]
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(context.applicationContext) as T
        }
    }
}