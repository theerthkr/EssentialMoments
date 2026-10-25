package com.theerthkr.essentialmoments

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ImageInfo(
    val name: String,
    val date: String,
    val sizeStr: String,
    val dimensions: String,
    val megapixels: String,
    val mimeType: String,
    val make: String?,
    val model: String?,
    val iso: String?,
    val exposureTime: String?,
    val aperture: String?,
    val path: String?
)

fun getImageInfo(context: Context, uriString: String): ImageInfo {
    val uri = Uri.parse(uriString)

    var name = "Unknown"
    var dateMs = 0L
    var sizeBytes = 0L
    var width = 0
    var height = 0
    var mimeType = "Unknown"
    var path: String? = null

    if (uri.scheme == "content") {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: "Unknown"

                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (dateIdx != -1) dateMs = cursor.getLong(dateIdx) * 1000

                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeIdx != -1) sizeBytes = cursor.getLong(sizeIdx)

                val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                if (widthIdx != -1) width = cursor.getInt(widthIdx)

                val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                if (heightIdx != -1) height = cursor.getInt(heightIdx)

                val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                if (mimeIdx != -1) mimeType = cursor.getString(mimeIdx) ?: "Unknown"

                val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIdx != -1) path = cursor.getString(dataIdx)
            }
        }
    } else {
        path = uri.path
        name = uri.lastPathSegment ?: "Unknown"
    }

    var make: String? = null
    var model: String? = null
    var iso: String? = null
    var exposureTime: String? = null
    var aperture: String? = null

    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val exif = ExifInterface(inputStream)
            make = exif.getAttribute(ExifInterface.TAG_MAKE)
            model = exif.getAttribute(ExifInterface.TAG_MODEL)
            iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            val expTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            if (expTime != null) {
                val expDouble = expTime.toDoubleOrNull()
                if (expDouble != null && expDouble > 0) {
                    val denom = (1 / expDouble).toInt()
                    exposureTime = "1/$denom s"
                } else {
                    exposureTime = "${expTime}s"
                }
            }
            val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            if (fNumber != null) {
                aperture = "f $fNumber"
            }
            if (width == 0 || height == 0) {
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            }
            if (dateMs == 0L) {
                val datetime = exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (datetime != null) {
                    try {
                        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                        dateMs = sdf.parse(datetime)?.time ?: 0L
                    } catch (e: Exception) {}
                }
            }
        }
    } catch (e: Exception) {
        // Handle exception
    }

    val dateStr = if (dateMs > 0) {
        SimpleDateFormat("dd MMMM yyyy 'at' HH:mm", Locale.getDefault()).format(Date(dateMs))
    } else {
        "Unknown Date"
    }

    val mb = sizeBytes / (1024.0 * 1024.0)
    val sizeStr = if (mb > 0.1) String.format("%.1f MB", mb) else String.format("%d KB", sizeBytes / 1024)

    val mp = (width * height) / 1000000.0
    val megapixels = if (mp > 0.1) String.format("%.1fMP", mp) else ""

    val dimStr = if (width > 0 && height > 0) "$width × $height" else ""

    val mimeDisplay = mimeType.replace("image/", "").uppercase(Locale.getDefault())

    return ImageInfo(
        name = name,
        date = dateStr,
        sizeStr = sizeStr,
        dimensions = dimStr,
        megapixels = megapixels,
        mimeType = mimeDisplay,
        make = make,
        model = model,
        iso = iso,
        exposureTime = exposureTime,
        aperture = aperture,
        path = path
    )
}
