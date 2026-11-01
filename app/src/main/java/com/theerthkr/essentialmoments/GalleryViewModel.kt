package com.theerthkr.essentialmoments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theerthkr.essentialmoments.media.MediaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for device photo gallery and albums.
 *
 * Delegates photo querying and URI normalization directly to the deep [MediaCatalog] module.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val mediaCatalog: MediaCatalog = (application as App).mediaCatalog

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _images = MutableStateFlow<List<MediaImage>>(emptyList())
    val images: StateFlow<List<MediaImage>> = _images.asStateFlow()

    private val _allImages = MutableStateFlow<List<MediaImage>>(emptyList())
    val allImages: StateFlow<List<MediaImage>> = _allImages.asStateFlow()

    private val _isDescending = MutableStateFlow(true)
    val isDescending: StateFlow<Boolean> = _isDescending.asStateFlow()

    fun toggleSortOrder() {
        _isDescending.value = !_isDescending.value
        _allImages.value = _allImages.value.reversed()
    }

    fun fetchAlbums() {
        viewModelScope.launch {
            val albumsList = withContext(Dispatchers.IO) {
                mediaCatalog.albums()
            }
            _albums.value = albumsList
        }
    }

    fun fetchAllImages() {
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) {
                mediaCatalog.allPhotos()
            }
            _allImages.value = if (_isDescending.value) photos else photos.reversed()
        }
    }

    fun fetchImagesForAlbum(albumId: String) {
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) {
                mediaCatalog.albumPhotos(albumId)
            }
            _images.value = photos
        }
    }
}
