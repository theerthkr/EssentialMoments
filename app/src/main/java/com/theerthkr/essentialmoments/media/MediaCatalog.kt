package com.theerthkr.essentialmoments.media

import com.theerthkr.essentialmoments.Album
import com.theerthkr.essentialmoments.MediaImage

/**
 * Deep module interface for accessing device photos.
 *
 * Hides MediaStore cursor management, URI normalization, and column parsing
 * behind a small interface. All returned URIs use canonical
 * content://media/external/images/media/<id> format.
 */
interface MediaCatalog {

    /** Returns canonical content:// URI strings for all photos on the device. */
    fun allPhotoUris(): List<String>

    /** Returns all device albums with cover URIs and photo counts. */
    fun albums(): List<Album>

    /** Returns photos belonging to [albumId], newest first. */
    fun albumPhotos(albumId: String): List<MediaImage>

    /** Returns all photos on the device, newest first. */
    fun allPhotos(): List<MediaImage>

    /**
     * Normalizes any photo URI string (picker document URI, file path, or content URI)
     * to a canonical content://media/external/images/media/<id> format.
     * Returns null if the URI cannot be resolved to a known MediaStore photo.
     */
    fun normalizeUri(uri: String): String?
}
