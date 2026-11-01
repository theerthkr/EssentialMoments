package com.theerthkr.essentialmoments

data class Album (
    val id: String,
    val name: String,
    val photoCount: Int,
    val coverUri: String
)

data class MediaImage(
    val id: Long,
    val uri: String,
    val albumId: String,
    /** Capture/added time in epoch milliseconds. */
    val dateTaken: Long
)