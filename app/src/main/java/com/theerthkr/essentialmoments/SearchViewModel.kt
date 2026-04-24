package com.theerthkr.essentialmoments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

// --- Data Models ---

data class IndexedImage(
    val mediaStoreId: Long,
    val uri: String,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexedImage) return false
        return mediaStoreId == other.mediaStoreId
    }
    override fun hashCode(): Int = mediaStoreId.hashCode()
}

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

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // true while text embedding + similarity search is running
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var indexedImages: MutableList<IndexedImage> = mutableListOf()
    private val indexFile: File get() = File(appContext.filesDir, "image_index.json")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadIndex()
        }
    }

    // --- Public API ---

    fun onQueryChanged(text: String) {
        _query.value = text
        if (text.isBlank()) {
            _searchResults.value = emptyList()
        }
    }

    fun search(query: String) {
        if (query.isBlank() || indexedImages.isEmpty()) {
            Log.d("SearchViewModel", "search() bailed: blank=${query.isBlank()}, indexSize=${indexedImages.size}")
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _isSearching.value = true
            try {
                val textProcessor = SiglipTextProcessor(appContext)
                val queryEmbedding = textProcessor.getEmbedding(query)
                textProcessor.close()

                Log.d("SearchViewModel", "Query embedding first 5: ${queryEmbedding.take(5)}")

                val scored = indexedImages.map { img ->
                    val score = cosineSimilarity(queryEmbedding, img.embedding)
                    SearchResult(uri = img.uri, score = score)
                }

                val top5 = scored.sortedByDescending { it.score }.take(5)
                Log.d("SearchViewModel", "Top 5 scores: ${top5.map { "%.4f".format(it.score) }}")

                val results = scored

                    .sortedByDescending { it.score }

                _searchResults.value = results
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Search error", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun indexImages(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            _indexingState.value = IndexingState.Indexing(0, uris.size)

            val imageProcessor = SiglipImageProcessor()
            var successCount = 0

            uris.forEachIndexed { index, uri ->
                try {
                    val mediaId = getMediaStoreId(uri)
                    if (indexedImages.any { it.mediaStoreId == mediaId }) {
                        _indexingState.value = IndexingState.Indexing(index + 1, uris.size)
                        return@forEachIndexed
                    }

                    // Take a permanent read grant so Coil can load this URI later
                    try {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        Log.w("SearchViewModel", "Could not persist URI permission for $uri")
                    }

                    val bitmap = uriToBitmap(uri) ?: return@forEachIndexed

                    val resized = imageProcessor.resize(bitmap, 224, 224)
                    val hwcPixels = imageProcessor.extractHwcPixels(resized)
                    val rescaled = imageProcessor.rescale(hwcPixels, 255.0f)
                    val normalized = imageProcessor.normalize(
                        rescaled,
                        floatArrayOf(0.5f, 0.5f, 0.5f),
                        floatArrayOf(0.5f, 0.5f, 0.5f)
                    )
                    val nchwFloatArray = imageProcessor.transposeHwcToNchw(normalized, 224, 224)

                    val model = com.google.ai.edge.litert.CompiledModel.create(
                        appContext.assets,
                        "siglip2_base_patch16-224.tflite",
                        com.google.ai.edge.litert.CompiledModel.Options(
                            com.google.ai.edge.litert.Accelerator.NPU
                        )
                    )
                    val inputBuffers = model.createInputBuffers()
                    val outputBuffers = model.createOutputBuffers()
                    inputBuffers[0].writeFloat(nchwFloatArray)
                    model.run(inputBuffers, outputBuffers)
                    val rawEmbedding = outputBuffers[0].readFloat()
                    model.close()

                    val embedding = l2Normalize(rawEmbedding)

                    indexedImages.add(
                        IndexedImage(
                            mediaStoreId = mediaId,
                            uri = uri.toString(),
                            embedding = embedding
                        )
                    )
                    successCount++

                } catch (e: Exception) {
                    Log.e("SearchViewModel", "Failed to index $uri", e)
                }

                _indexingState.value = IndexingState.Indexing(index + 1, uris.size)
            }

            saveIndex()
            _indexingState.value = IndexingState.Done(successCount)

            kotlinx.coroutines.delay(2000)
            _indexingState.value = IndexingState.Idle
        }
    }

    // --- Private Helpers ---

    private fun getMediaStoreId(uri: Uri): Long {
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media._ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                } else uri.hashCode().toLong()
            } ?: uri.hashCode().toLong()
        } catch (e: Exception) {
            uri.hashCode().toLong()
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(appContext.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(appContext.contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.e("SearchViewModel", "Failed to decode bitmap for $uri", e)
            null
        }
    }

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var sumOfSquares = 0.0f
        for (value in embedding) sumOfSquares += value * value
        val magnitude = sqrt(sumOfSquares)
        if (magnitude == 0.0f) return embedding
        return FloatArray(embedding.size) { i -> embedding[i] / magnitude }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    // --- JSON Persistence ---

    private fun saveIndex() {
        try {
            val jsonArray = JSONArray()
            for (img in indexedImages) {
                val obj = JSONObject()
                obj.put("mediaStoreId", img.mediaStoreId)
                obj.put("uri", img.uri)
                obj.put("embedding", img.embedding.joinToString(","))
                jsonArray.put(obj)
            }
            indexFile.writeText(jsonArray.toString())
            Log.d("SearchViewModel", "Index saved: ${indexedImages.size} images")
        } catch (e: Exception) {
            Log.e("SearchViewModel", "Failed to save index", e)
        }
    }

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val jsonArray = JSONArray(indexFile.readText())
            val loaded = mutableListOf<IndexedImage>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val embedding = obj.getString("embedding")
                    .split(",")
                    .map { it.toFloat() }
                    .toFloatArray()
                loaded.add(
                    IndexedImage(
                        mediaStoreId = obj.getLong("mediaStoreId"),
                        uri = obj.getString("uri"),
                        embedding = embedding
                    )
                )
            }
            indexedImages = loaded
            Log.d("SearchViewModel", "Index loaded: ${indexedImages.size} images")
        } catch (e: Exception) {
            Log.e("SearchViewModel", "Failed to load index", e)
        }
    }

    // --- Factory ---

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(context.applicationContext) as T
        }
    }
}