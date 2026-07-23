package com.theerthkr.essentialmoments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.MediaStore
import android.util.Log

data class ImageMetadata(
    val name: String,
    val date: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val make: String,
    val model: String,
    val aperture: String,
    val exposureTime: String,
    val iso: String,
    val focalLength: String,
    val path: String
)

fun getImageMetadata(context: Context, uriString: String): ImageMetadata {
    var name = "Unknown"
    var sizeBytes = 0L
    var date = ""
    var width = 0
    var height = 0
    var make = ""
    var model = ""
    var aperture = ""
    var exposureTime = ""
    var iso = ""
    var focalLength = ""
    var path = ""

    val uri = Uri.parse(uriString)

    // Attempt to get basic info from ContentResolver
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) name = cursor.getString(nameIndex)

                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)

                // Try MediaStore specific columns if available
                val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                if (dataIndex != -1) path = cursor.getString(dataIndex) ?: ""
            }
        }
    } catch (e: Exception) {
        Log.e("ImageMetadata", "Error querying ContentResolver", e)
    }

    // Fallback path
    if (path.isEmpty()) {
        path = uriString
    }

    // Attempt to read Exif
    var inputStream: InputStream? = null
    try {
        inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream != null) {
            val exif = ExifInterface(inputStream)
            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
            model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
            aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: ""
            exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: ""
            iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: ""
            focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH) ?: ""

            // Format datetime
            val dateTimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (!dateTimeStr.isNullOrEmpty()) {
                try {
                    val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    val parsedDate: Date? = parser.parse(dateTimeStr)
                    if (parsedDate != null) {
                        val formatter = SimpleDateFormat("dd MMMM yyyy 'at' HH:mm", Locale.getDefault())
                        date = formatter.format(parsedDate)
                    }
                } catch (e: Exception) {
                    date = dateTimeStr
                }
            }
        }
    } catch (e: Exception) {
        Log.e("ImageMetadata", "Error reading Exif", e)
    } finally {
        inputStream?.close()
    }

    // Format shutter speed
    if (exposureTime.isNotEmpty()) {
        try {
            val et = exposureTime.toDouble()
            if (et < 1.0) {
                exposureTime = "1/${Math.round(1.0 / et)}"
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    return ImageMetadata(
        name = name,
        date = date,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        make = make,
        model = model,
        aperture = aperture,
        exposureTime = exposureTime,
        iso = iso,
        focalLength = focalLength,
        path = path
    )
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
