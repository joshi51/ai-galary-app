package com.localphotoai.photomanager.data.media

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.database.getStringOrNull
import androidx.exifinterface.media.ExifInterface
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val TAG = "MediaStoreDataSource"

/**
 * Reads photo metadata from Android's MediaStore. Never opens image bytes except to read EXIF
 * tags (orientation, GPS, capture date) for photos the caller has already determined are new or
 * changed — a light, EXIF-free query is used for change detection.
 */
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    /** MediaStore's monotonically increasing change token, or null on API < 30 / if unavailable. */
    fun queryGeneration(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
        } catch (t: Throwable) {
            null
        }
    }

    /** Cheap id + dateModified scan across all image volumes — no EXIF, no dimensions. */
    fun queryLightSnapshot(): List<LightPhotoRecord> {
        val records = mutableListOf<LightPhotoRecord>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                records += LightPhotoRecord(
                    mediaStoreId = cursor.getLong(idCol),
                    dateModifiedMs = cursor.getLong(dateModifiedCol) * 1000L,
                )
            }
        }
        return records
    }

    /** Full metadata (dimensions, MIME type, dates, EXIF orientation/GPS) for the given ids only. */
    fun queryFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata> {
        if (mediaStoreIds.isEmpty()) return emptyList()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val placeholders = mediaStoreIds.joinToString(",") { "?" }
        val selection = "${MediaStore.Images.Media._ID} IN ($placeholders)"
        val selectionArgs = mediaStoreIds.map { it.toString() }.toTypedArray()

        val results = mutableListOf<PhotoMetadata>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val relativePathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                var indexError: String? = null
                var orientation = 0
                var latitude: Double? = null
                var longitude: Double? = null

                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        orientation = exifOrientationToDegrees(
                            exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            ),
                        )
                        val latLong = FloatArray(2)
                        if (exif.getLatLong(latLong)) {
                            latitude = latLong[0].toDouble()
                            longitude = latLong[1].toDouble()
                        }
                    }
                } catch (t: Throwable) {
                    logger.warn(TAG, "EXIF read failed for photo $id", t)
                    indexError = "EXIF read failed: ${t.message}"
                }

                val dateTakenMs = if (!cursor.isNull(dateTakenCol)) cursor.getLong(dateTakenCol) else null

                results += PhotoMetadata(
                    mediaStoreId = id,
                    uri = uri.toString(),
                    filename = cursor.getStringOrNull(nameCol) ?: "IMG_$id",
                    mimeType = cursor.getStringOrNull(mimeCol) ?: "image/*",
                    sizeBytes = cursor.getLong(sizeCol),
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol),
                    dateAddedMs = cursor.getLong(dateAddedCol) * 1000L,
                    dateModifiedMs = cursor.getLong(dateModifiedCol) * 1000L,
                    dateTakenMs = dateTakenMs,
                    orientationDegrees = orientation,
                    latitude = latitude,
                    longitude = longitude,
                    indexError = indexError,
                    relativePath = cursor.getStringOrNull(relativePathCol),
                )
            }
        }
        return results
    }

    private fun exifOrientationToDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
