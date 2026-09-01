package com.localphotoai.photomanager.fsops

import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import java.io.File
import javax.inject.Inject

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

private val ALLOWED_ROOTS = listOf("Pictures/", "DCIM/")

/**
 * Validates one operation immediately before it runs — re-checked at execution time even though
 * the same checks already informed the review UI, since state can change between review and
 * confirmation (a photo could be deleted, a destination could collide from an unrelated write).
 */
class PlanValidator @Inject constructor(
    private val photoRepository: PhotoRepository,
) {
    suspend fun validate(operation: OrganizationOperation, otherDestinationsInPlan: Set<String>): ValidationResult {
        when (operation.opType) {
            OperationType.MOVE, OperationType.COPY, OperationType.RENAME -> {
                val sourceUri = operation.source
                    ?: return ValidationResult.Invalid("${operation.opType} requires a source photo")
                val photoId = sourceUri.substringAfterLast("/").toLongOrNull()
                    ?: return ValidationResult.Invalid("Malformed source URI: $sourceUri")
                if (photoRepository.fetchById(photoId) == null) {
                    return ValidationResult.Invalid("Source photo $photoId no longer exists")
                }
            }
            OperationType.CREATE_FOLDER, OperationType.CREATE_ALBUM -> {
                // no source photo required
            }
        }

        val canonicalRoot = canonicalizeOrNull(operation.destination)
            ?: return ValidationResult.Invalid("Destination path is not valid: ${operation.destination}")
        if (operation.opType != OperationType.CREATE_ALBUM && ALLOWED_ROOTS.none { canonicalRoot.startsWith(it) }) {
            return ValidationResult.Invalid("Destination must be under Pictures/ or DCIM/: ${operation.destination}")
        }

        if (operation.destination in otherDestinationsInPlan) {
            return ValidationResult.Invalid("Destination collides with another operation in this plan: ${operation.destination}")
        }

        return ValidationResult.Valid
    }

    /** Rejects any `..` segment or absolute-path escape outright — never "sanitizes" a
     * traversal attempt into something else, per ARCHITECTURE.md §19's threat model. */
    private fun canonicalizeOrNull(path: String): String? {
        if (path.split("/").any { it == ".." }) return null
        if (File(path).isAbsolute) return null
        return path
    }
}
