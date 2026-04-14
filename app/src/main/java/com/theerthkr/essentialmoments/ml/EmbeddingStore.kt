package com.theerthkr.essentialmoments.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Flat-file embedding store — append-only binary file + JSON index.
 *
 * On-disk layout:
 *   embeddings.bin  — raw float32 values, each record = 768 × 4 = 3072 bytes
 *   embeddings.idx  — JSON: { "imageId": byteOffset, ... }
 *
 * Performance notes:
 *   - store()      : appends to bin, updates in-memory index (O(1))
 *   - flushIndex() : writes index to disk — call once per batch, not per store()
 *   - search()     : single RAF session, O(N) scan — ~30ms for 10k images
 *   - get()        : O(1) seek by imageId
 *
 * Thread safety: store(), flushIndex(), clearAll() are @Synchronized.
 *                search() and get() are read-only and safe from any thread.
 */
class EmbeddingStore(context: Context) {

    companion object {
        private const val TAG = "EmbeddingStore"
        const val DIM = ImageEmbedder.EMBEDDING_DIM       // 768
        private const val BYTES_PER_EMBEDDING = DIM * 4  // float32 = 4 bytes each
        private const val BIN_FILE = "embeddings.bin"
        private const val IDX_FILE = "embeddings.idx"
    }

    private val binFile = File(context.filesDir, BIN_FILE)
    private val idxFile = File(context.filesDir, IDX_FILE)

    // In-memory index: imageId → byte offset in bin file
    private val index = mutableMapOf<String, Long>()

    init {
        loadIndex()
    }

    // ── Write ─────────────────────────────────────────────────────

    /**
     * Appends an embedding for [imageId].
     * Skips silently if [imageId] is already indexed.
     * Does NOT flush to disk — call flushIndex() after your batch.
     */
    @Synchronized
    fun store(imageId: String, embedding: FloatArray) {
        require(embedding.size == DIM) {
            "Expected $DIM floats, got ${embedding.size}"
        }
        if (index.containsKey(imageId)) return

        val offset = binFile.length()

        RandomAccessFile(binFile, "rw").use { raf ->
            raf.seek(offset)
            val buf = ByteBuffer
                .allocate(BYTES_PER_EMBEDDING)
                .order(ByteOrder.LITTLE_ENDIAN)   // consistent across devices
            embedding.forEach { buf.putFloat(it) }
            raf.write(buf.array())
        }

        index[imageId] = offset
        // NOTE: intentionally NOT saving index here — caller must call flushIndex()
        Log.v(TAG, "Stored $imageId at offset=$offset  total=${index.size}")
    }

    /**
     * Persists the in-memory index to disk.
     * Call once after each batch of store() calls — not inside the per-image loop.
     */
    @Synchronized
    fun flushIndex() {
        try {
            val json = JSONObject()
            index.forEach { (k, v) -> json.put(k, v) }
            idxFile.writeText(json.toString())
            Log.d(TAG, "Index flushed: ${index.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "flushIndex failed: ${e.message}")
        }
    }

    // ── Read ──────────────────────────────────────────────────────

    /** Returns the stored embedding for [imageId], or null if not indexed. */
    fun get(imageId: String): FloatArray? {
        val offset = index[imageId] ?: return null
        return RandomAccessFile(binFile, "r").use { raf ->
            raf.seek(offset)
            val raw = ByteArray(BYTES_PER_EMBEDDING)
            raf.readFully(raw)
            ByteBuffer.wrap(raw)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .let { fb -> FloatArray(DIM) { fb.get() } }
        }
    }

    // ── Search ────────────────────────────────────────────────────

    /**
     * Linear scan — returns top [topK] results sorted by cosine similarity.
     * Both query and stored embeddings must be L2-normalised beforehand;
     * cosine similarity then equals the dot product.
     *
     * Timing: ~30ms for 10k images on a mid-range device (single RAF session).
     */
    fun search(queryEmbedding: FloatArray, topK: Int = 20): List<SearchResult> {
        require(queryEmbedding.size == DIM) {
            "Query must be $DIM-dimensional, got ${queryEmbedding.size}"
        }
        if (index.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()
        val raw = ByteArray(BYTES_PER_EMBEDDING)

        RandomAccessFile(binFile, "r").use { raf ->
            for ((imageId, offset) in index) {
                raf.seek(offset)
                raf.readFully(raw)

                val fb = ByteBuffer.wrap(raw)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()

                // Dot product of two unit vectors = cosine similarity
                var score = 0f
                for (i in 0 until DIM) score += queryEmbedding[i] * fb.get(i)

                results.add(SearchResult(imageId, score))
            }
        }

        return results.sortedByDescending { it.score }.take(topK)
    }

    // ── Utility ───────────────────────────────────────────────────

    fun isIndexed(imageId: String) = index.containsKey(imageId)
    fun indexedCount()             = index.size
    fun allIndexedIds(): Set<String> = index.keys.toSet()

    /** Wipes everything — useful when you want a clean re-index during debug */
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
            Log.d(TAG, "Loaded index: ${index.size} entries from ${idxFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "loadIndex failed — index may be corrupt: ${e.message}")
        }
    }
}

data class SearchResult(val imageId: String, val score: Float)