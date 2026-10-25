package com.theerthkr.essentialmoments

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/**
 * ObjectBox entity storing a photo's canonical URI and its 768-D SigLIP 2 embedding.
 *
 * The @HnswIndex enables native C++ approximate nearest-neighbor search via
 * ObjectBox's built-in HNSW graph, so callers never load all embeddings into
 * Kotlin heap memory for ranking.
 */
@Entity
data class ImageEntity(
    @Id var id: Long = 0,
    @Index val uri: String = "",
    @HnswIndex(dimensions = 768, distanceType = VectorDistanceType.COSINE)
    val embedding: FloatArray = FloatArray(0)
)
