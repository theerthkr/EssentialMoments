package com.theerthkr.essentialmoments.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Projection used by [RoomVectorIndex.queryAll] so search avoids loading IDs. */
data class ImageEmbedding(val uri: String, val embedding: FloatArray)

@Dao
interface ImageIndexDao {

    /**
     * Returns every indexed URI for one model. The adapter diffs this set against
     * the candidate URIs, which avoids a bind-variable limit on large libraries.
     */
    @Query("SELECT uri FROM image_index WHERE modelId = :modelId")
    suspend fun indexedUris(modelId: String): List<String>

    /**
     * Inserts or replaces rows. The unique (modelId, uri) index turns this into
     * an upsert, so re-indexing is idempotent.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ImageIndexEntity>)

    @Query("SELECT uri, embedding FROM image_index WHERE modelId = :modelId")
    suspend fun embeddingsForModel(modelId: String): List<ImageEmbedding>

    @Query("SELECT COUNT(*) FROM image_index")
    suspend fun count(): Int
}
