package com.theerthkr.essentialmoments

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index


@Entity
data class ImageEntity(
    @Id var id: Long = 0,
    @Index val uri: String,
    val embedding: FloatArray // ObjectBox supports FloatArrays!
)