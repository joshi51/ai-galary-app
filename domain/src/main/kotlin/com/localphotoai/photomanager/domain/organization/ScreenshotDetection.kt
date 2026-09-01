package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo

/** Filename-pattern heuristic matching Android's own screenshot naming convention. Shared by
 * [ScreenshotOrganizationStrategy] and [ArchiveOrganizationStrategy] so both agree on what
 * counts as a screenshot. */
fun isScreenshot(photo: Photo): Boolean = photo.filename.contains("screenshot", ignoreCase = true)
