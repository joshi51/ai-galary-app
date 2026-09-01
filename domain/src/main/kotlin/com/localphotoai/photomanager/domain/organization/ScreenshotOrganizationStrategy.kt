package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo

private const val SCREENSHOTS_FOLDER = "Pictures/Screenshots"

object ScreenshotOrganizationStrategy {
    fun build(photos: List<Photo>): List<OrganizationOperation> {
        val matches = photos.filter { isScreenshot(it) && it.relativePath?.startsWith("Pictures/Screenshots") != true }
        if (matches.isEmpty()) return emptyList()

        val createFolder = OrganizationOperation(
            opType = OperationType.CREATE_FOLDER,
            source = null,
            destination = SCREENSHOTS_FOLDER,
            reason = "Destination folder for detected screenshots",
            confidence = 1.0f,
        )
        val moves = matches.map { photo ->
            OrganizationOperation(
                opType = OperationType.MOVE,
                source = photo.uri,
                destination = "$SCREENSHOTS_FOLDER/${photo.filename}",
                reason = "Filename matches screenshot pattern",
                confidence = 0.9f,
            )
        }
        return listOf(createFolder) + moves
    }
}
