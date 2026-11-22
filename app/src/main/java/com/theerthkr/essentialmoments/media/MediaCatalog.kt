package com.theerthkr.essentialmoments.media

import com.theerthkr.essentialmoments.Album
import com.theerthkr.essentialmoments.MediaImage
import kotlinx.coroutines.flow.Flow

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
     * Emits whenever the device photo library changes (a photo is added,
     * removed, edited, or a selected-photo set changes).
     *
     * The flow is cold: observing it registers a MediaStore ContentObserver and
     * cancelling the collection unregisters it. Collectors should debounce or
     * conflate bursts themselves; MediaStore can emit several events for one
     * change (insert + update).
     */
    fun observeChanges(): Flow<Unit>

    /**
     * Normalizes any photo URI string (picker document URI, file path, or content URI)
     * to a canonical content://media/external/images/media/<id> format.
     * Returns null if the URI cannot be resolved to a known MediaStore photo.
     */
    fun normalizeUri(uri: String): String?
}
