package com.theerthkr.essentialmoments.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.theerthkr.essentialmoments.Album
import com.theerthkr.essentialmoments.MediaImage

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
    }

    override fun allPhotoUris(): List<String> {
        val uris = mutableListOf<String>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        contentResolver.query(EXTERNAL_URI, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                uris.add(ContentUris.withAppendedId(EXTERNAL_URI, id).toString())
            }
        }
        return uris
    }

    override fun albums(): List<Album> {
        val albumsMap = mutableMapOf<String, Album>()
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media._ID
        )
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
        return albumsMap.values.toList()
    }

    override fun albumPhotos(albumId: String): List<MediaImage> {
        val images = mutableListOf<MediaImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN
        )
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        contentResolver.query(
            EXTERNAL_URI, projection, selection, arrayOf(albumId),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(EXTERNAL_URI, id).toString()
                val date = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                images.add(MediaImage(id, uri, albumId, date))
            }
        }
        return images
    }

    override fun allPhotos(): List<MediaImage> {
        val images = mutableListOf<MediaImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        contentResolver.query(
            EXTERNAL_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(EXTERNAL_URI, id).toString()
                val date = cursor.getLong(dateCol)
                images.add(MediaImage(id, uri, "AllPhotos", date))
            }
        }
        return images
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
            contentResolver.query(EXTERNAL_URI, projection, selection, arrayOf(path), null)?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    return ContentUris.withAppendedId(EXTERNAL_URI, id).toString()
                }
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
