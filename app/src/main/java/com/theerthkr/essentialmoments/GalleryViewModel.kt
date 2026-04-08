package com.theerthkr.essentialmoments

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theerthkr.essentialmoments.ml.ImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _images = MutableStateFlow<List<MediaImage>>(emptyList())
    val images: StateFlow<List<MediaImage>> = _images.asStateFlow()

    // ── Albums ────────────────────────────────────────────────────

    fun fetchAlbums() {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = getApplication<Application>().contentResolver

            val projection = arrayOf(
                MediaStore.Images.Media._ID,                   // ← needed for content URI
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                // NOTE: MediaStore.Images.Media.DATA (file paths) intentionally omitted —
                //       unreliable on Android 10+ with scoped storage.
            )

            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            ) ?: return@launch

            val albumsMap = mutableMapOf<String, Album>()

            cursor.use {
                val idCol     = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol   = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (it.moveToNext()) {
                    val imageId  = it.getLong(idCol)
                    val bucketId = it.getString(bucketCol) ?: "unknown"
                    val name     = it.getString(nameCol) ?: "Unnamed Album"

                    // Build a proper content:// URI — works on all API levels including 29+
                    val coverUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId
                    )

                    val existing = albumsMap[bucketId]
                    albumsMap[bucketId] = if (existing != null) {
                        existing.copy(photoCount = existing.photoCount + 1)
                    } else {
                        Album(
                            id         = bucketId,
                            name       = name,
                            coverUri   = coverUri.toString(),
                            photoCount = 1
                        )
                    }
                }
            }

            _albums.value = albumsMap.values
                .sortedByDescending { it.photoCount }
                .toList()
        }
    }

    // ── Images for a specific album ───────────────────────────────

    fun fetchImagesForAlbum(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val imagesList    = mutableListOf<MediaImage>()
            val contentResolver = getApplication<Application>().contentResolver

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN
            )
            val selection     = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            val selectionArgs = arrayOf(albumId)

            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection, selectionArgs,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            ) ?: return@launch

            cursor.use {
                val idCol        = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

                while (it.moveToNext()) {
                    val imageId   = it.getLong(idCol)
                    val dateTaken = it.getLong(dateTakenCol)

                    // Correct scoped-storage URI — never use DATA column
                    val uri: Uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId
                    )

                    imagesList.add(
                        MediaImage(
                            id        = imageId,
                            uri       = uri.toString(),
                            albumId   = albumId,
                            dateTaken = dateTaken
                        )
                    )
                }
            }

            _images.value = imagesList
        }
    }
}
