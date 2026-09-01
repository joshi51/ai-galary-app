package com.localphotoai.photomanager.fsops

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.localphotoai.photomanager.domain.organization.AlbumRepository
import com.localphotoai.photomanager.domain.organization.OperationPreviousState
import com.localphotoai.photomanager.domain.organization.OperationRecord
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OperationUndoExecutor
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.UndoResult
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import javax.inject.Inject

data class OperationExecutionResult(
    val operationId: Long,
    val success: Boolean,
    val error: String? = null,
    /** Captured only for a successful, reversible operation — see [OperationPreviousState]. */
    val previousState: OperationPreviousState? = null,
    /** Whether [com.localphotoai.photomanager.domain.organization.UndoLastOrganizationUseCase]
     * can reverse this operation, independent of whether it succeeded (checked together with
     * [success] when recording history — see `RecordOrganizationExecutionUseCase`). */
    val reversible: Boolean = false,
)

/**
 * Performs one confirmed operation. `CREATE_FOLDER` has no independent execution step — Android's
 * scoped storage has no primitive for an empty folder, so a folder only ever exists as a
 * byproduct of the `MOVE`(s) that populate it (the destination path passed to `ContentResolver`
 * for those `MOVE`s is what actually creates the folder). `CREATE_ALBUM` never touches
 * MediaStore/the filesystem at all — see [executeAlbumOperation].
 *
 * Also implements [OperationUndoExecutor] (Phase 10) — reversing an already-recorded
 * [OperationRecord] is the mirror image of executing it, and needs the same
 * `ContentResolver`/`AlbumRepository` access, so it lives on the same class rather than a
 * separate one with duplicated MediaStore plumbing.
 */
class PlanExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val planValidator: PlanValidator,
) : OperationUndoExecutor {
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
                OperationType.CREATE_FOLDER -> OperationExecutionResult(operation.id, success = true, reversible = false)
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
            val albumId = albumRepository.createAlbum(operation.destination, operation.memberPhotoIds)
            OperationExecutionResult(
                operation.id,
                success = true,
                previousState = OperationPreviousState(createdAlbumId = albumId),
                reversible = true,
            )
        } catch (t: Throwable) {
            OperationExecutionResult(operation.id, success = false, error = t.message ?: "Unknown error")
        }
    }

    /** [OperationUndoExecutor] — reverses one already-executed, successful [OperationRecord].
     * Never called for a record whose [OperationRecord.reversible] is false or whose
     * [OperationRecord.result] wasn't a success — [UndoLastOrganizationUseCase] filters those out
     * before this is invoked. */
    override suspend fun undo(record: OperationRecord): UndoResult {
        val previousState = record.previousState
        return try {
            when (record.opType) {
                OperationType.MOVE, OperationType.RENAME -> undoMoveOrRename(record, requireNotNull(previousState))
                OperationType.COPY -> undoCopy(record, requireNotNull(previousState))
                OperationType.CREATE_ALBUM -> undoCreateAlbum(record, requireNotNull(previousState))
                OperationType.CREATE_FOLDER -> UndoResult(record.id, success = true)
            }
        } catch (e: RecoverableSecurityException) {
            UndoResult(record.id, success = false, error = "NEEDS_CONSENT")
        } catch (t: Throwable) {
            UndoResult(record.id, success = false, error = t.message ?: "Unknown error")
        }
    }

    private fun undoMoveOrRename(record: OperationRecord, previousState: OperationPreviousState): UndoResult {
        val photoId = requireNotNull(record.source).substringAfterLast("/").toLong()
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, requireNotNull(previousState.previousDisplayName))
            if (record.opType == OperationType.MOVE) {
                put(MediaStore.Images.Media.RELATIVE_PATH, requireNotNull(previousState.previousRelativePath))
            }
        }
        val updated = context.contentResolver.update(uri, values, null, null)
        return UndoResult(record.id, success = updated > 0)
    }

    /** Undoing a COPY means deleting the row it created — the source photo was never touched. */
    private fun undoCopy(record: OperationRecord, previousState: OperationPreviousState): UndoResult {
        val createdUri = Uri.parse(requireNotNull(previousState.createdUri))
        val deleted = context.contentResolver.delete(createdUri, null, null)
        return UndoResult(record.id, success = deleted > 0)
    }

    /** Undoing CREATE_ALBUM means deleting the (virtual, Room-only) album it created. */
    private suspend fun undoCreateAlbum(record: OperationRecord, previousState: OperationPreviousState): UndoResult {
        albumRepository.deleteAlbum(requireNotNull(previousState.createdAlbumId))
        return UndoResult(record.id, success = true)
    }

    private suspend fun moveOrRename(operation: OrganizationOperation, renameOnly: Boolean): OperationExecutionResult {
        val photoId = requireNotNull(operation.source).substringAfterLast("/").toLong()
        val sourcePhoto = photoRepository.fetchById(photoId)
            ?: return OperationExecutionResult(operation.id, success = false, error = "Source photo no longer exists")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val destinationPath = operation.destination
        val destinationFolder = destinationPath.substringBeforeLast("/")
        val destinationFilename = destinationPath.substringAfterLast("/")

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destinationFilename)
            if (!renameOnly) put(MediaStore.Images.Media.RELATIVE_PATH, "$destinationFolder/")
        }
        val updated = context.contentResolver.update(uri, values, null, null)
        return OperationExecutionResult(
            operation.id,
            success = updated > 0,
            previousState = OperationPreviousState(
                previousDisplayName = sourcePhoto.filename,
                previousRelativePath = sourcePhoto.relativePath,
            ),
            reversible = updated > 0,
        )
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
        return OperationExecutionResult(
            operation.id,
            success = true,
            previousState = OperationPreviousState(createdUri = newUri.toString()),
            reversible = true,
        )
    }
}
