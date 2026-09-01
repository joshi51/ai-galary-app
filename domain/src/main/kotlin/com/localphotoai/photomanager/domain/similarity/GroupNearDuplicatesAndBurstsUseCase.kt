package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger

private const val TAG = "GroupNearDuplicatesAndBurstsUseCase"

/** Named, documented, untuned heuristics — see [groupNearDuplicatesAndBursts]'s doc comment. */
const val NEAR_DUPLICATE_HAMMING_THRESHOLD = 8
const val BURST_TIME_WINDOW_MS = 2_000L

/** Re-groups every photo's stored perceptual hash + date into near-duplicate/burst groups, from scratch each run. */
class GroupNearDuplicatesAndBurstsUseCase(
    private val repository: PhotoGroupRepository,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<Int> = try {
        val hashes = repository.fetchAllHashes()
        val groups = groupNearDuplicatesAndBursts(hashes, NEAR_DUPLICATE_HAMMING_THRESHOLD, BURST_TIME_WINDOW_MS)
        repository.replaceNearDuplicateAndBurstGroups(groups)
        logger.info(TAG, "Near-duplicate/burst grouping complete: ${groups.size} group(s)")
        AppResult.Success(groups.size)
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown grouping error"
        logger.error(TAG, "Near-duplicate/burst grouping failed", t)
        AppResult.Failure(AppError.Io(message = "Near-duplicate/burst grouping failed: $message", cause = t))
    }
}
