package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TripOrganizationStrategy {
    fun build(photos: List<Photo>, dateHint: String?, nameHint: String?): List<OrganizationOperation> {
        val gpsPhotos = photos.mapNotNull { photo ->
            val lat = photo.latitude
            val lon = photo.longitude
            val taken = photo.dateTakenMs
            if (lat != null && lon != null && taken != null) {
                GpsTaggedPhoto(photo.mediaStoreId, lat, lon, taken)
            } else {
                null
            }
        }
        val clusters = TripClusterer.cluster(gpsPhotos)
        if (clusters.isEmpty()) return emptyList()

        val hintMs = dateHint?.let { parseIsoDateOrNull(it) }
        val chosen = if (hintMs != null) {
            clusters.filter { hintMs in it.startDateMs..it.endDateMs }
                .minByOrNull { kotlin.math.abs(it.startDateMs - hintMs) }
                ?: clusters.minBy { kotlin.math.abs(it.startDateMs - hintMs) }
        } else {
            clusters.maxBy { it.endDateMs }
        }

        val albumName = nameHint?.takeIf { it.isNotBlank() } ?: run {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            "Trip ${format.format(java.util.Date(chosen.startDateMs))}–${format.format(java.util.Date(chosen.endDateMs))}"
        }

        return listOf(
            OrganizationOperation(
                opType = OperationType.CREATE_ALBUM,
                source = null,
                destination = albumName,
                reason = "Photos clustered by location and date proximity",
                confidence = chosen.tightness,
                memberPhotoIds = chosen.photoIds,
            ),
        )
    }

    private fun parseIsoDateOrNull(value: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(value)?.time
    } catch (e: Exception) {
        null
    }
}
