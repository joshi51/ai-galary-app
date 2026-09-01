package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary

private const val ARCHIVE_FOLDER = "Pictures/Archive"

private data class ArchiveMatch(val photo: Photo, val reason: String, val confidence: Float)

object ArchiveOrganizationStrategy {
    /** Named, documented, untuned heuristic — same honest treatment as every prior phase's
     * thresholds. ~12 months. */
    const val SCREENSHOT_AGE_MS = 365L * 24 * 60 * 60 * 1000

    fun build(photos: List<Photo>, duplicateGroups: List<DuplicateGroupSummary>, nowMs: Long): List<OrganizationOperation> {
        val byId = photos.associateBy { it.mediaStoreId }

        val oldScreenshots = photos.filter { photo ->
            isScreenshot(photo) && photo.dateTakenMs != null && (nowMs - photo.dateTakenMs) > SCREENSHOT_AGE_MS
        }.map { ArchiveMatch(it, "Screenshot older than 12 months", 0.7f) }

        val nonRepresentativeDuplicates = duplicateGroups.flatMap { group ->
            val representativeId = group.photoIds.min()
            group.photoIds.filter { it != representativeId }
        }.mapNotNull { byId[it] }.map { ArchiveMatch(it, "Duplicate of another photo already in your library", 0.95f) }

        val matches = (oldScreenshots + nonRepresentativeDuplicates).distinctBy { it.photo.mediaStoreId }
        if (matches.isEmpty()) return emptyList()

        val createFolder = OrganizationOperation(
            opType = OperationType.CREATE_FOLDER,
            source = null,
            destination = ARCHIVE_FOLDER,
            reason = "Destination folder for archive candidates",
            confidence = 1.0f,
        )
        val moves = matches.map { match ->
            OrganizationOperation(
                opType = OperationType.MOVE,
                source = match.photo.uri,
                destination = "$ARCHIVE_FOLDER/${match.photo.filename}",
                reason = match.reason,
                confidence = match.confidence,
            )
        }
        return listOf(createFolder) + moves
    }
}
