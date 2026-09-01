package com.localphotoai.photomanager.fsops

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.localphotoai.photomanager.domain.organization.AlbumRepository
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import javax.inject.Inject

data class OperationExecutionResult(val operationId: Long, val success: Boolean, val error: String? = null)

/**
 * Performs one confirmed operation. `CREATE_FOLDER` has no independent execution step — Android's
 * scoped storage has no primitive for an empty folder, so a folder only ever exists as a
 * byproduct of the `MOVE`(s) that populate it (the destination path passed to `ContentResolver`
 * for those `MOVE`s is what actually creates the folder). `CREATE_ALBUM` never touches
 * MediaStore/the filesystem at all — see [executeAlbumOperation].
 */
class PlanExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val planValidator: PlanValidator,
) {
    /** Re-validates, then performs a MOVE/COPY/RENAME. Call only after any needed
     * write-consent [android.content.IntentSender] has already succeeded (MOVE/RENAME on
     * API 30+) — see `OrganizationReviewScreen` for that sequencing. */
    suspend fun executeFileOperation(operation: OrganizationOperation): OperationExecutionResult {
        val validation = planValidator.validate(operation, otherDestinationsInPlan = emptySet())
        if (validation is ValidationResult.Invalid) {
            return OperationExecutionResult(operation.id, success = false, error = validation.reason)
        }

        return try {
            when (operation.opType) {
                OperationType.MOVE -> moveOrRename(operation, renameOnly = false)
                OperationType.RENAME -> moveOrRename(operation, renameOnly = true)
                OperationType.COPY -> copy(operation)
                OperationType.CREATE_FOLDER -> OperationExecutionResult(operation.id, success = true)
                OperationType.CREATE_ALBUM -> error("CREATE_ALBUM must go through executeAlbumOperation")
            }
        } catch (e: RecoverableSecurityException) {
            OperationExecutionResult(operation.id, success = false, error = "NEEDS_CONSENT")
        } catch (t: Throwable) {
            OperationExecutionResult(operation.id, success = false, error = t.message ?: "Unknown error")
        }
    }

    /** CREATE_ALBUM only writes to Room — no MediaStore/filesystem interaction. */
    suspend fun executeAlbumOperation(operation: OrganizationOperation): OperationExecutionResult {
        require(operation.opType == OperationType.CREATE_ALBUM)
        return try {
            albumRepository.createAlbum(operation.destination, operation.memberPhotoIds)
            OperationExecutionResult(operation.id, success = true)
        } catch (t: Throwable) {
            OperationExecutionResult(operation.id, success = false, error = t.message ?: "Unknown error")
        }
    }

    private fun moveOrRename(operation: OrganizationOperation, renameOnly: Boolean): OperationExecutionResult {
        val photoId = requireNotNull(operation.source).substringAfterLast("/").toLong()
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val destinationPath = operation.destination
        val destinationFolder = destinationPath.substringBeforeLast("/")
        val destinationFilename = destinationPath.substringAfterLast("/")

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destinationFilename)
            if (!renameOnly) put(MediaStore.Images.Media.RELATIVE_PATH, "$destinationFolder/")
        }
        val updated = context.contentResolver.update(uri, values, null, null)
        return OperationExecutionResult(operation.id, success = updated > 0)
    }

    private suspend fun copy(operation: OrganizationOperation): OperationExecutionResult {
        val photoId = requireNotNull(operation.source).substringAfterLast("/").toLong()
        val sourcePhoto = photoRepository.fetchById(photoId)
            ?: return OperationExecutionResult(operation.id, success = false, error = "Source photo not found")
        val sourceUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)

        val destinationFolder = operation.destination.substringBeforeLast("/")
        val destinationFilename = operation.destination.substringAfterLast("/")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destinationFilename)
            put(MediaStore.Images.Media.MIME_TYPE, sourcePhoto.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "$destinationFolder/")
        }
        val newUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return OperationExecutionResult(operation.id, success = false, error = "Could not create destination entry")

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            context.contentResolver.openOutputStream(newUri)?.use { output ->
                input.copyTo(output)
            }
        }
        return OperationExecutionResult(operation.id, success = true)
    }
}
