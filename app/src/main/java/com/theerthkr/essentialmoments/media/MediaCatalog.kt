package com.theerthkr.essentialmoments.media

import com.theerthkr.essentialmoments.Album
import com.theerthkr.essentialmoments.MediaImage
import kotlinx.coroutines.flow.Flow

/**
 * A readable photo plus the metadata used to decide whether a stored embedding
 * still represents its current content.
 *
 * The fingerprint is intentionally metadata-only. Hashing every file on every
 * scan would require reading the whole library before we could skip unchanged
 * photos, which defeats the purpose of the index.
 */
data class PhotoCandidate(
    val uri: String,
    val fingerprint: String
)

/**
 * Builds the change-detection fingerprint for one MediaStore row.
 *
 * [dateModifiedSeconds] is the primary signal: Android updates it whenever a
 * writer replaces the file. Size and dimensions catch providers that rewrite
 * content without refreshing the timestamp. The display name is deliberately
 * excluded because renaming a file does not change the pixels or the media URI.
 */
internal fun photoFingerprint(
    dateModifiedSeconds: Long,
    sizeBytes: Long,
    width: Int,
    height: Int,
    dateTakenMs: Long
): String = "$dateModifiedSeconds:$sizeBytes:$width:$height:$dateTakenMs"

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

    /** Returns every readable photo with its change-detection fingerprint. */
    fun photoCandidates(): List<PhotoCandidate>

    /**
     * Resolves [uri] to its canonical form and returns its current fingerprint,
     * or null when the photo no longer exists or cannot be read.
     */
    fun photoCandidate(uri: String): PhotoCandidate?

    /**
     * Returns the subset of [candidates] that still exist in MediaStore.
     * Used to keep a cached search result from opening a deleted photo.
     */
    fun existingUris(candidates: Collection<String>): Set<String>

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
