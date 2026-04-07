package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Flat-file embedding store.
 *
 * Layout on disk:
 *   embeddings.bin  — raw float32 values, each embedding = 768 × 4 bytes = 3072 bytes
 *   embeddings.idx  — JSON: { "imageId": byteOffset, ... }
 *
 * This gives O(1) lookup by imageId and O(N) scan for search.
 * For <10k images, a full scan takes ~30ms on a mid-range device.
 */
class EmbeddingStore(context: Context) {

    companion object {
        private const val TAG = "EmbeddingStore"
        const val DIM = ImageEmbedder.EMBEDDING_DIM          // 768
        private const val BYTES_PER_EMBEDDING = DIM * 4     // float32 = 4 bytes
        private const val BIN_FILE = "embeddings.bin"
        private const val IDX_FILE = "embeddings.idx"
    }

    private val binFile = File(context.filesDir, BIN_FILE)
    private val idxFile = File(context.filesDir, IDX_FILE)

    // In-memory index: imageId (String) → byte offset in bin file
    private val index = mutableMapOf<String, Long>()

    init {
        loadIndex()
    }

    // ── Write ─────────────────────────────────────────────────────

    /**
     * Appends an embedding for [imageId]. Skips if already indexed.
     * Thread-safe: synchronized on the bin file.
     */
    @Synchronized
    fun store(imageId: String, embedding: FloatArray) {
        require(embedding.size == DIM) { "Expected $DIM floats, got ${embedding.size}" }
        if (index.containsKey(imageId)) return  // already indexed

        val offset = binFile.length()

        RandomAccessFile(binFile, "rw").use { raf ->
            raf.seek(offset)
            val buf = ByteBuffer.allocate(BYTES_PER_EMBEDDING).order(ByteOrder.nativeOrder())
            embedding.forEach { buf.putFloat(it) }
            raf.write(buf.array())
        }

        index[imageId] = offset
        saveIndex()

        Log.d(TAG, "Stored embedding for $imageId at offset=$offset  total=${index.size}")
    }

    // ── Read ──────────────────────────────────────────────────────

    /** Returns the embedding for [imageId], or null if not indexed. */
    fun get(imageId: String): FloatArray? {
        val offset = index[imageId] ?: return null

        return RandomAccessFile(binFile, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(BYTES_PER_EMBEDDING)
            raf.readFully(buf)
            ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .let { fb -> FloatArray(DIM) { fb.get() } }
        }
    }

    // ── Search ────────────────────────────────────────────────────

    /**
     * Linear scan — returns top [topK] imageIds sorted by cosine similarity
     * to [queryEmbedding]. Both query and stored embeddings must be L2-normalised,
     * so cosine similarity = dot product.
     */
    fun search(queryEmbedding: FloatArray, topK: Int = 20): List<SearchResult> {
        require(queryEmbedding.size == DIM)
        if (index.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()

        RandomAccessFile(binFile, "r").use { raf ->
            val buf = ByteArray(BYTES_PER_EMBEDDING)

            for ((imageId, offset) in index) {
                raf.seek(offset)
                raf.readFully(buf)
                val fb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder()).asFloatBuffer()

                // Dot product = cosine similarity (both vectors are unit-normalised)
                var score = 0f
                for (i in 0 until DIM) score += queryEmbedding[i] * fb.get(i)

                results.add(SearchResult(imageId, score))
            }
        }

        return results.sortedByDescending { it.score }.take(topK)
    }

    // ── Utility ───────────────────────────────────────────────────

    fun isIndexed(imageId: String) = index.containsKey(imageId)
    fun indexedCount() = index.size
    fun allIndexedIds(): Set<String> = index.keys.toSet()

    /** Clears everything — useful for re-indexing during debug */
    @Synchronized
    fun clearAll() {
        binFile.delete()
        idxFile.delete()
        index.clear()
        Log.d(TAG, "Store cleared")
    }

    // ── Index persistence ─────────────────────────────────────────

    private fun loadIndex() {
        if (!idxFile.exists()) return
        try {
            val json = JSONObject(idxFile.readText())
            json.keys().forEach { key -> index[key] = json.getLong(key) }
            Log.d(TAG, "Loaded index: ${index.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "loadIndex failed: ${e.message}")
        }
    }

    @Synchronized
    private fun saveIndex() {
        try {
            val json = JSONObject()
            index.forEach { (k, v) -> json.put(k, v) }
            idxFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "saveIndex failed: ${e.message}")
        }
    }
}

data class SearchResult(val imageId: String, val score: Float)