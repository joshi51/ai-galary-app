package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger

private const val TAG = "DetectDuplicatesUseCase"

/** Re-groups every photo's stored content hash into exact-duplicate groups, from scratch each run. */
class DetectDuplicatesUseCase(
    private val repository: PhotoGroupRepository,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<Int> = try {
        val hashes = repository.fetchAllHashes()
        val groups = groupByExactHash(hashes)
        repository.replaceDuplicateGroups(groups)
        logger.info(TAG, "Duplicate grouping complete: ${groups.size} group(s)")
        AppResult.Success(groups.size)
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown grouping error"
        logger.error(TAG, "Duplicate grouping failed", t)
        AppResult.Failure(AppError.Io(message = "Duplicate grouping failed: $message", cause = t))
    }
}
