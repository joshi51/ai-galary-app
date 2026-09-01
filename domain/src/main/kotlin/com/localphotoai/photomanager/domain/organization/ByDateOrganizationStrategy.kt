package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val RAW_CAMERA_FOLDER = "DCIM/Camera"

/** UTC, not the device's local timezone — a deterministic, timezone-independent choice for
 * which month-folder a photo lands in (avoids a photo taken near local midnight landing in a
 * different folder depending on the device's timezone setting, and keeps this pure function
 * testable without depending on the JVM's default timezone). */
private fun yearMonthFormat() = SimpleDateFormat("yyyy/yyyy-MM", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

object ByDateOrganizationStrategy {
    fun build(photos: List<Photo>): List<OrganizationOperation> {
        val eligible = photos.filter {
            it.dateTakenMs != null && it.relativePath?.startsWith(RAW_CAMERA_FOLDER) == true
        }
        if (eligible.isEmpty()) return emptyList()

        val format = yearMonthFormat()
        val byMonth = eligible.groupBy { format.format(Date(it.dateTakenMs!!)) }

        return byMonth.flatMap { (monthFolder, monthPhotos) ->
            val destinationFolder = "Pictures/$monthFolder"
            val createFolder = OrganizationOperation(
                opType = OperationType.CREATE_FOLDER,
                source = null,
                destination = destinationFolder,
                reason = "Grouping by capture date",
                confidence = 1.0f,
            )
            val moves = monthPhotos.map { photo ->
                OrganizationOperation(
                    opType = OperationType.MOVE,
                    source = photo.uri,
                    destination = "$destinationFolder/${photo.filename}",
                    reason = "Grouping by capture date",
                    confidence = 1.0f,
                )
            }
            listOf(createFolder) + moves
        }
    }
}
