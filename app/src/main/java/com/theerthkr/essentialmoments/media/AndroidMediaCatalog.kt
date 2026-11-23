package com.theerthkr.essentialmoments.media

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.theerthkr.essentialmoments.Album
import com.theerthkr.essentialmoments.MediaImage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Production adapter for [MediaCatalog] backed by Android MediaStore.
 *
 * All returned URIs are normalized to canonical content://media/external/images/media/<id>.
 * Consolidates the four separate cursor loops that previously lived in
 * GalleryViewModel (3 cursors) and IndexingWorker (1 cursor).
 */
class AndroidMediaCatalog(private val contentResolver: ContentResolver) : MediaCatalog {

    companion object {
        private val EXTERNAL_URI: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        private val CANDIDATE_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN
        )
    }

    override fun allPhotoUris(): List<String> {
        val uris = mutableListOf<String>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        try {
            contentResolver.query(EXTERNAL_URI, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    uris.add(ContentUris.withAppendedId(EXTERNAL_URI, id).toString())
                }
            }
        } catch (_: SecurityException) {
            // Photo access may have been revoked between the permission check
            // and this query. Treat it as an empty library instead of crashing.
            return emptyList()
        }
        return uris
    }

    override fun photoCandidates(): List<PhotoCandidate> {
        val candidates = mutableListOf<PhotoCandidate>()
        try {
            contentResolver.query(
                EXTERNAL_URI, CANDIDATE_PROJECTION, null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    candidates.add(readCandidate(cursor))
                }
            }
        } catch (_: SecurityException) {
            // Access may have been revoked between the permission check and this
            // query. Treat it as an empty library; a later scan reconciles.
            return emptyList()
        }
        return candidates
    }

    override fun photoCandidate(uri: String): PhotoCandidate? {
        val canonical = normalizeUri(uri) ?: return null
        try {
            contentResolver.query(
                Uri.parse(canonical), CANDIDATE_PROJECTION, null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return readCandidate(cursor, canonical)
            }
        } catch (_: SecurityException) {
            return null
        }
        return null
    }

    override fun existingUris(candidates: Collection<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val wanted = candidates.toHashSet()
        // Reading the currently visible ID set is safer than an `_ID IN (...)`
        // query: under partial access, asking the provider about unselected IDs
        // can fail. allPhotoUris() already returns exactly the readable set.
        return allPhotoUris().filterTo(HashSet()) { it in wanted }
    }

    private fun readCandidate(cursor: android.database.Cursor, uri: String? = null): PhotoCandidate {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

        val photoUri = uri ?: ContentUris.withAppendedId(
            EXTERNAL_URI, cursor.getLong(idCol)
        ).toString()
        val fingerprint = photoFingerprint(
            dateModifiedSeconds = cursor.getLong(modifiedCol),
            sizeBytes = cursor.getLong(sizeCol),
            width = cursor.getInt(widthCol),
            height = cursor.getInt(heightCol),
            dateTakenMs = cursor.getLong(takenCol)
        )
        return PhotoCandidate(photoUri, fingerprint)
    }

    override fun albums(): List<Album> {
        val albumsMap = mutableMapOf<String, Album>()
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media._ID
        )
        try {
            contentResolver.query(
                EXTERNAL_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                while (cursor.moveToNext()) {
                    val bucketId = cursor.getString(bucketIdCol) ?: "unknown"
                    val name = cursor.getString(nameCol) ?: "Unnamed Album"
                    val photoId = cursor.getLong(idCol)
                    val coverUri = ContentUris.withAppendedId(EXTERNAL_URI, photoId).toString()

                    val existing = albumsMap[bucketId]
                    if (existing != null) {
                        albumsMap[bucketId] = existing.copy(photoCount = existing.photoCount + 1)
                    } else {
                        albumsMap[bucketId] = Album(
                            id = bucketId,
                            name = name,
                            coverUri = coverUri,
                            photoCount = 1
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return albumsMap.values.toList()
    }

    override fun albumPhotos(albumId: String): List<MediaImage> =
        queryImages(
            selection = "${MediaStore.Images.Media.BUCKET_ID} = ?",
            selectionArgs = arrayOf(albumId),
            albumId = albumId
        )

    override fun allPhotos(): List<MediaImage> =
        queryImages(selection = null, selectionArgs = null, albumId = "AllPhotos")

    override fun observeChanges(): Flow<Unit> = callbackFlow {
        // notifyForDescendants=true also delivers item-level changes
        // (content://.../images/media/<id>), which is what inserts and edits emit.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(EXTERNAL_URI, true, observer)
        awaitClose { contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    /**
     * Reads photo metadata and orders rows by the date the gallery should display.
     *
     * [MediaStore.Images.Media.DATE_TAKEN] is the capture time from the image's EXIF
     * metadata. Screenshots, downloads, and older imports can have no capture time;
     * for those, [MediaStore.Images.Media.DATE_ADDED] is used instead. MediaStore
     * cannot sort by that combined value, so the rows are ordered after querying.
     * The provider's DATE_ADDED order remains the stable tie-breaker.
     */
    private fun queryImages(
        selection: String?,
        selectionArgs: Array<String>?,
        albumId: String
    ): List<MediaImage> {
        val images = mutableListOf<MediaImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        try {
            contentResolver.query(
                EXTERNAL_URI, projection, selection, selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(EXTERNAL_URI, id).toString()
                    // DATE_TAKEN is epoch milliseconds; DATE_ADDED is epoch seconds.
                    val takenMs = cursor.getLong(takenCol)
                    val addedMs = cursor.getLong(addedCol) * 1000L
                    images.add(MediaImage(id, uri, albumId, if (takenMs > 0L) takenMs else addedMs))
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return images.sortedByDescending { it.dateTaken }
    }

    override fun normalizeUri(uri: String): String? {
        val parsed = Uri.parse(uri)

        // Already canonical: content://media/external/images/media/<id>
        if (parsed.authority == "media" && parsed.pathSegments.let {
                it.size >= 4 && it[0] == "external" && it[1] == "images" && it[2] == "media"
            }) {
            return uri
        }

        // Document picker URI: content://com.android.providers.media.documents/document/image%3A123
        if (parsed.authority?.contains("media.documents") == true) {
            val docId = parsed.lastPathSegment // "image:123"
            val mediaId = docId?.substringAfter("image:")?.toLongOrNull()
            if (mediaId != null) {
                return ContentUris.withAppendedId(EXTERNAL_URI, mediaId).toString()
            }
        }

        // File path → query MediaStore by DATA column
        if (parsed.scheme == "file" || parsed.scheme == null) {
            val path = parsed.path ?: return null
            val projection = arrayOf(MediaStore.Images.Media._ID)
            @Suppress("DEPRECATION")
            val selection = "${MediaStore.Images.Media.DATA} = ?"
            try {
                contentResolver.query(EXTERNAL_URI, projection, selection, arrayOf(path), null)?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        return ContentUris.withAppendedId(EXTERNAL_URI, id).toString()
                    }
                }
            } catch (_: SecurityException) {
                return null
            }
        }

        // Content URI from photo picker (content://media/picker/...) → resolve to canonical
        if (parsed.scheme == "content") {
            try {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                contentResolver.query(parsed, projection, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(0)
                        return ContentUris.withAppendedId(EXTERNAL_URI, id).toString()
                    }
                }
            } catch (_: Exception) { /* URI not queryable; fall through */ }
        }

        return null
    }
}
