package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.search.PhotoSortOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Shared parameter validation for every `Tool` implementation — a hallucinated/malformed value
 * from the LLM must never reach a `:domain` use case, per ARCHITECTURE.md §19. */
object ToolValidator {

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    fun parseIsoDate(value: String?): AppResult<Long?> {
        if (value.isNullOrBlank()) return AppResult.Success(null)
        return try {
            AppResult.Success(isoFormat().parse(value)?.time)
        } catch (e: Exception) {
            AppResult.Failure(AppError.Validation("Invalid date \"$value\" — expected yyyy-MM-dd."))
        }
    }

    fun parseSortOrder(value: String?): AppResult<PhotoSortOrder> {
        if (value.isNullOrBlank()) return AppResult.Success(PhotoSortOrder.NEWEST)
        return when (value.uppercase(Locale.US)) {
            "NEWEST" -> AppResult.Success(PhotoSortOrder.NEWEST)
            "LARGEST" -> AppResult.Success(PhotoSortOrder.LARGEST)
            "SMALLEST" -> AppResult.Success(PhotoSortOrder.SMALLEST)
            else -> AppResult.Failure(AppError.Validation("Invalid sortBy \"$value\" — expected newest/largest/smallest."))
        }
    }

    fun requirePhotoId(photoId: Long?): AppResult<Long> {
        if (photoId == null) return AppResult.Failure(AppError.Validation("photoId is required."))
        return AppResult.Success(photoId)
    }

    fun parseOrganizationCategory(value: String?): AppResult<com.localphotoai.photomanager.domain.organization.OrganizationCategory> {
        if (value.isNullOrBlank()) return AppResult.Failure(AppError.Validation("category is required."))
        return try {
            AppResult.Success(com.localphotoai.photomanager.domain.organization.OrganizationCategory.valueOf(value.uppercase(Locale.US)))
        } catch (e: IllegalArgumentException) {
            AppResult.Failure(AppError.Validation("Unknown organization category \"$value\"."))
        }
    }
}
