package com.theerthkr.essentialmoments

import android.app.Application
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    // 1. A private 'pipe' that we can write to
    private val _albums = MutableStateFlow<List<Album>>(emptyList())

    // 2. A public 'pipe' that the UI can only read from
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()


    private val _isDescending = MutableStateFlow(true)
    val isDescending: StateFlow<Boolean> = _isDescending.asStateFlow()

    fun toggleSortOrder() {
        _isDescending.value = !_isDescending.value
        _allImages.value = _allImages.value.reversed()
    }

    // 3. The function to trigger the search
    fun fetchAlbums() {
        viewModelScope.launch(Dispatchers.IO) { // 🧵 Background thread
            val contentResolver = getApplication<Application>().contentResolver

            val projection = arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATA
            )
            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )

            cursor?.use {
                val albumsMap = mutableMapOf<String, Album>()

// 1. Get the column indices once before the loop
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    // 1. Use ?: to provide fallbacks for null values
                    val bucketId = cursor.getString(idCol) ?: "unknown_folder"
                    val name = cursor.getString(nameCol) ?: "Unnamed Album"
                    val imagePath = cursor.getString(dataCol) ?: ""

                    val existingAlbum = albumsMap[bucketId]

                    if (existingAlbum != null) {
                        albumsMap[bucketId] = existingAlbum.copy(
                            photoCount = existingAlbum.photoCount + 1
                        )
                    } else {
                        albumsMap[bucketId] = Album(
                            id = bucketId,
                            name = name,
                            coverUri = imagePath,
                            photoCount = 1
                        )
                    }
                }
                _albums.value = albumsMap.values.toList()
            }
        }
    }

    // A new state flow to hold the images of the selected album
    private val _images = MutableStateFlow<List<MediaImage>>(emptyList())
    val images: StateFlow<List<MediaImage>> = _images.asStateFlow()

    // A new state flow to hold all images
    private val _allImages = MutableStateFlow<List<MediaImage>>(emptyList())
    val allImages: StateFlow<List<MediaImage>> = _allImages.asStateFlow()

    fun fetchAllImages() {
        viewModelScope.launch(Dispatchers.IO) {
            val imagesList = mutableListOf<MediaImage>() // 1. Create a temporary list

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA, // 2. Added DATA for the file path
                MediaStore.Images.Media.DATE_ADDED
            )

            val cursor = getApplication<Application>().contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursor?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val dateColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (c.moveToNext()) {
                    val id = c.getLong(idColumn)
                    val path = c.getString(dataColumn) ?: ""
                    val date = c.getLong(dateColumn)

                    // 3. Build the MediaImage object and add it to our list[cite: 18]
                    imagesList.add(MediaImage(id, path, "AllPhotos", date))
                }

                // 4. Push the full list into the StateFlow "pipe"[cite: 18]
                _allImages.value = imagesList
                Log.d("GalleryViewModel", "Successfully fetched ${imagesList.size} images.")
            } ?: Log.e("GalleryViewModel", "Query returned a null cursor")
        }
    }

    fun fetchImagesForAlbum(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val imagesList = mutableListOf<MediaImage>()
            val contentResolver = getApplication<Application>().contentResolver

            // 1. We only want these specific columns
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_TAKEN
            )

            // 2. The Filter: "Only give me images where the BUCKET_ID is [this ID]"
            val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            val selectionArgs = arrayOf(albumId)

            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC" // Newest first
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val path = it.getString(dataCol)

                    imagesList.add(MediaImage(id, path, albumId, 0L))
                }
            }

            // 3. Update the pipe so the UI sees the photos
            _images.value = imagesList
        }
    }
}