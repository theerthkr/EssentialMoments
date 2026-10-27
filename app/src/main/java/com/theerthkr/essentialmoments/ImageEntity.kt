package com.theerthkr.essentialmoments

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/**
 * ObjectBox entity storing a photo's canonical URI, its 768-D SigLIP 2 embedding,
 * and the [modelId] of the model that generated the embedding.
 *
 * [modelId] scopes search: an embedding is only compared against other embeddings
 * produced by the same model, so text and vision embeddings from different models
 * can never be mixed.
 */
@Entity
data class ImageEntity(
    @Id var id: Long = 0,
    @Index val uri: String = "",
    @Index val modelId: String = "",
    @HnswIndex(dimensions = 768, distanceType = VectorDistanceType.COSINE)
    val embedding: FloatArray = FloatArray(0)
)
