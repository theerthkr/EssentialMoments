package com.theerthkr.essentialmoments.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Projection used by [RoomVectorIndex.queryAll] so search avoids loading IDs. */
data class ImageEmbedding(val uri: String, val embedding: FloatArray)

/** Stored identity of an indexed photo, used to detect replacements and renames. */
data class IndexedState(val uri: String, val fingerprint: String)

@Dao
interface ImageIndexDao {

    /**
     * Returns every indexed URI and its fingerprint for one model. The adapter
     * compares this against the current MediaStore catalog, which avoids a
     * bind-variable limit on large libraries.
     */
    @Query("SELECT uri, fingerprint FROM image_index WHERE modelId = :modelId")
    suspend fun indexedStates(modelId: String): List<IndexedState>

    /**
     * Inserts or replaces rows. The unique (modelId, uri) index turns this into
     * an upsert, so re-indexing is idempotent.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ImageIndexEntity>)

    /** Deletes exactly the supplied URIs for one model. Callers chunk large lists. */
    @Query("DELETE FROM image_index WHERE modelId = :modelId AND uri IN (:uris)")
    suspend fun deleteByUris(modelId: String, uris: List<String>)

    /** Removes every embedding, e.g. when photo access is revoked entirely. */
    @Query("DELETE FROM image_index")
    suspend fun clearAll()

    @Query("SELECT uri, embedding FROM image_index WHERE modelId = :modelId")
    suspend fun embeddingsForModel(modelId: String): List<ImageEmbedding>

    @Query("SELECT COUNT(*) FROM image_index")
    suspend fun count(): Int
}
