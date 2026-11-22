package com.theerthkr.essentialmoments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theerthkr.essentialmoments.media.MediaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for device photo gallery and albums.
 *
 * Delegates photo querying and URI normalization directly to the deep [MediaCatalog] module.
 *
 * Data is refreshed reactively: a MediaStore ContentObserver ([MediaCatalog.observeChanges])
 * feeds a conflated refresh queue, so new screenshots, downloads, or deletions appear
 * without leaving and returning to the app. Only collections that have actually been
 * requested by a screen are re-queried — opening Albums never re-reads every photo.
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

    /** Which collections this screen currently cares about. */
    private var wantsAlbums = false
    private var wantsAllImages = false
    private var wantedAlbumId: String? = null

    /**
     * Conflated queue: a burst of MediaStore notifications collapses into one
     * pending refresh, and refreshes run one at a time so an older query can
     * never overwrite a newer result.
     */
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            // Trailing debounce: MediaStore often emits insert + update for the
            // same photo. Waiting briefly collapses them into a single refresh.
            mediaCatalog.observeChanges().collectLatest {
                delay(MEDIA_CHANGE_DEBOUNCE_MS)
                refreshRequests.trySend(Unit)
            }
        }
        viewModelScope.launch {
            for (ignored in refreshRequests) {
                refreshRequestedCollections()
            }
        }
    }

    fun toggleSortOrder() {
        _isDescending.value = !_isDescending.value
        _allImages.value = _allImages.value.reversed()
    }

    fun fetchAlbums() {
        wantsAlbums = true
        refreshRequests.trySend(Unit)
    }

    fun fetchAllImages() {
        wantsAllImages = true
        refreshRequests.trySend(Unit)
    }

    fun fetchImagesForAlbum(albumId: String) {
        wantedAlbumId = albumId
        refreshRequests.trySend(Unit)
    }

    private suspend fun refreshRequestedCollections() {
        if (wantsAlbums) {
            val albumsList = withContext(Dispatchers.IO) { mediaCatalog.albums() }
            _albums.value = albumsList
        }
        if (wantsAllImages) {
            val photos = withContext(Dispatchers.IO) { mediaCatalog.allPhotos() }
            _allImages.value = if (_isDescending.value) photos else photos.reversed()
        }
        wantedAlbumId?.let { albumId ->
            val photos = withContext(Dispatchers.IO) { mediaCatalog.albumPhotos(albumId) }
            // Ignore a result for an album that is no longer the one being shown.
            if (wantedAlbumId == albumId) {
                _images.value = photos
            }
        }
    }

    /** Drops cached rows when photo access is revoked. */
    fun clear() {
        _albums.value = emptyList()
        _images.value = emptyList()
        _allImages.value = emptyList()
    }

    private companion object {
        /**
         * Long enough to merge the multiple notifications a single MediaStore
         * insert produces, short enough that a new photo feels immediate.
         */
        const val MEDIA_CHANGE_DEBOUNCE_MS = 350L
    }
}
