package com.theerthkr.essentialmoments

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Formats an exposure duration the way camera apps do:
 * fractions for sub-second exposures ("1/125 s") and plain seconds for
 * one second or longer ("2 s").
 *
 * The previous reciprocal formatting truncated the denominator, so a 2-second
 * exposure produced an invalid "1/0 s".
 */
internal fun formatExposureTime(seconds: Double): String? {
    if (!seconds.isFinite() || seconds <= 0.0) return null
    if (seconds >= 1.0) {
        val whole = seconds.roundToLong()
        val value = if (seconds == whole.toDouble()) {
            whole.toString()
        } else {
            String.format(Locale.getDefault(), "%.2f", seconds).trimEnd('0').trimEnd('.')
        }
        return "$value s"
    }
    val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
    return "1/$denominator s"
}

/**
 * Parses EXIF exposure time values, which can be decimal seconds ("2") or a
 * rational ("1/125"). Returns null when the value is not a positive duration.
 */
internal fun parseExposureSeconds(raw: String): Double? {
    val value = raw.toDoubleOrNull() ?: run {
        val parts = raw.split('/')
        if (parts.size == 2) {
            val numerator = parts[0].toDoubleOrNull()
            val denominator = parts[1].toDoubleOrNull()
            if (numerator != null && denominator != null && denominator != 0.0) {
                numerator / denominator
            } else {
                null
            }
        } else {
            null
        }
    } ?: return null
    return value.takeIf { it.isFinite() && it > 0.0 }
}

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
    val path: String?,
    val dateLabel: String = "Date",
    val dateAdded: String? = null
)

internal data class ImageDateSummary(
    val primaryDateMs: Long,
    val dateLabel: String,
    val dateAddedMs: Long?
)

/**
 * Resolves which timestamp to display as primary and how to label it.
 *
 * EXIF capture time is preferred. When absent (e.g. screenshots, downloads,
 * or imported photos without camera metadata), the file modification date is
 * shown and explicitly labeled as "Modified", while preserving the date added
 * to the device library so the user understands why the photo may be grouped
 * under "Today" in the gallery while showing an older modification date.
 */
internal fun resolveImageDate(
    dateTakenMs: Long,
    dateModifiedMs: Long,
    dateAddedMs: Long
): ImageDateSummary = when {
    dateTakenMs > 0L -> ImageDateSummary(
        primaryDateMs = dateTakenMs,
        dateLabel = "Captured",
        dateAddedMs = if (dateAddedMs > 0L) dateAddedMs else null
    )
    dateModifiedMs > 0L -> ImageDateSummary(
        primaryDateMs = dateModifiedMs,
        dateLabel = "Modified",
        dateAddedMs = if (dateAddedMs > 0L) dateAddedMs else null
    )
    dateAddedMs > 0L -> ImageDateSummary(
        primaryDateMs = dateAddedMs,
        dateLabel = "Added to library",
        dateAddedMs = null
    )
    else -> ImageDateSummary(
        primaryDateMs = 0L,
        dateLabel = "Date",
        dateAddedMs = null
    )
}

fun getImageInfo(context: Context, uriString: String): ImageInfo {
    val uri = Uri.parse(uriString)

    var name = "Unknown"
    var dateTakenMs = 0L
    var dateModifiedMs = 0L
    var dateAddedMs = 0L
    var sizeBytes = 0L
    var width = 0
    var height = 0
    var mimeType = "Unknown"
    var path: String? = null

    if (uri.scheme == "content") {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
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

                val takenIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                if (takenIdx != -1) dateTakenMs = cursor.getLong(takenIdx)

                val modifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (modifiedIdx != -1) {
                    val sec = cursor.getLong(modifiedIdx)
                    if (sec > 0L) dateModifiedMs = sec * 1000L
                }

                val addedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                if (addedIdx != -1) {
                    val sec = cursor.getLong(addedIdx)
                    if (sec > 0L) dateAddedMs = sec * 1000L
                }

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
        try {
            val file = uri.path?.let { java.io.File(it) }
            if (file != null && file.exists()) {
                dateModifiedMs = file.lastModified()
            }
        } catch (_: Exception) {}
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
                exposureTime = expTime
                    .let(::parseExposureSeconds)
                    ?.let(::formatExposureTime)
                    ?: "${expTime}s"
            }
            val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            if (fNumber != null) {
                aperture = "f $fNumber"
            }
            if (width == 0 || height == 0) {
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            }
            if (dateTakenMs <= 0L) {
                val datetime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                if (datetime != null) {
                    try {
                        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                        dateTakenMs = sdf.parse(datetime)?.time ?: 0L
                    } catch (e: Exception) {}
                }
            }
        }
    } catch (e: Exception) {
        // Handle exception
    }

    val dateSummary = resolveImageDate(
        dateTakenMs = dateTakenMs,
        dateModifiedMs = dateModifiedMs,
        dateAddedMs = dateAddedMs
    )

    val dateFormat = SimpleDateFormat("dd MMMM yyyy 'at' HH:mm", Locale.getDefault())
    val dateStr = if (dateSummary.primaryDateMs > 0) {
        dateFormat.format(Date(dateSummary.primaryDateMs))
    } else {
        "Unknown Date"
    }
    val dateAddedStr = dateSummary.dateAddedMs?.let { dateFormat.format(Date(it)) }

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
        path = path,
        dateLabel = dateSummary.dateLabel,
        dateAdded = dateAddedStr
    )
}
