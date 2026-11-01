package com.theerthkr.essentialmoments.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row storing a photo's canonical URI, its SigLIP embedding, and the
 * [modelId] of the model that generated the embedding.
 *
 * The unique [modelId] + [uri] index gives the index upsert semantics: indexing
 * the same photo twice for the same model replaces the old row instead of
 * creating a duplicate. [modelId] scopes search, so text and vision embeddings
 * from different models are never compared against each other.
 *
 * Embeddings are persisted as a BLOB by [FloatArrayConverter]. A 768-D
 * embedding costs 3,072 bytes per row.
 */
@Entity(
    tableName = "image_index",
    indices = [Index(value = ["modelId", "uri"], unique = true)]
)
data class ImageIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val modelId: String,
    val embedding: FloatArray
)
