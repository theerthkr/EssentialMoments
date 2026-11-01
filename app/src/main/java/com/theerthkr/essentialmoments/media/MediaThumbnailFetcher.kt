package com.theerthkr.essentialmoments.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Precision
import coil.size.pxOrElse

/**
 * Coil fetcher for gallery grids that uses MediaStore's pre-generated thumbnails
 * instead of reading and decoding the original full-resolution photo for every cell.
 *
 * The MediaProvider decodes and caches thumbnails in its own process, so a fast scroll
 * no longer queues dozens of 5–50 MB original-image reads and decodes in the app process.
 * Requests opt in explicitly via [THUMBNAIL_PARAM]; the full-screen viewer keeps using
 * the built-in original-image fetch.
 */
class MediaThumbnailFetcher(
    private val data: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val requestedSize = options.size.requestedSize()
        return try {
            val bitmap = options.context.contentResolver.loadThumbnail(data, requestedSize, null)
            bitmapResult(bitmap)
        } catch (_: Exception) {
            // Deleted or unthumbnailable media: decode the original at the requested
            // size rather than surfacing an error tile.
            bitmapResult(decodeOriginalAtSize(requestedSize))
        }
    }

    private fun bitmapResult(bitmap: Bitmap): FetchResult = DrawableResult(
        drawable = BitmapDrawable(options.context.resources, bitmap),
        isSampled = true,
        dataSource = DataSource.DISK
    )

    private fun decodeOriginalAtSize(requested: Size): Bitmap {
        val source = ImageDecoder.createSource(options.context.contentResolver, data)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxSide = maxOf(info.size.width, info.size.height)
            val target = maxOf(requested.width, requested.height)
            if (maxSide > target && target > 0) {
                val scale = target.toFloat() / maxSide
                val targetW = maxOf(1, (info.size.width * scale).toInt())
                val targetH = maxOf(1, (info.size.height * scale).toInt())
                decoder.setTargetSize(targetW, targetH)
            }
        }
    }

    private fun coil.size.Size.requestedSize(): Size {
        val width = this.width.pxOrElse { FALLBACK_SIZE }.coerceIn(MIN_SIZE, MAX_SIZE)
        val height = this.height.pxOrElse { FALLBACK_SIZE }.coerceIn(MIN_SIZE, MAX_SIZE)
        return Size(width, height)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (options.parameters.value<Boolean>(THUMBNAIL_PARAM) != true) return null
            if (!data.isMediaStoreImage()) return null
            return MediaThumbnailFetcher(data, options)
        }
    }

    companion object {
        const val THUMBNAIL_PARAM = "mediastore_thumbnail"

        private const val FALLBACK_SIZE = 512
        private const val MIN_SIZE = 64
        private const val MAX_SIZE = 1024

        private fun Uri.isMediaStoreImage(): Boolean {
            if (scheme != "content" || authority != MediaStore.AUTHORITY) return false
            val segments = pathSegments
            return segments.size >= 4 && segments[1] == "images"
        }
    }
}

/**
 * Builds a request that reads a MediaStore thumbnail at [sizePx].
 *
 * Use this for every grid cell / cover. Do not use it for the full-screen viewer,
 * which must keep loading the original.
 */
fun mediaThumbnailRequest(context: Context, uri: String, sizePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(sizePx)
        .precision(Precision.INEXACT)
        .setParameter(MediaThumbnailFetcher.THUMBNAIL_PARAM, true)
        .build()
