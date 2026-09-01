package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.similarity.DetectDuplicatesUseCase
import com.localphotoai.photomanager.domain.similarity.GroupNearDuplicatesAndBurstsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs both hash-based grouping passes (exact duplicates, then near-duplicate/burst) — combined
 * into one worker since both are cheap, DB-only, pure-grouping passes triggered by the same
 * event (hashing completion), with no meaningful reason to run them as separate WorkManager jobs.
 * Chained off [HashWorker]. Terminal — no further stage depends on this one.
 */
@HiltWorker
class HashGroupingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val detectDuplicatesUseCase: DetectDuplicatesUseCase,
    private val groupNearDuplicatesAndBurstsUseCase: GroupNearDuplicatesAndBurstsUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val duplicateResult = detectDuplicatesUseCase()
        val nearDuplicateResult = groupNearDuplicatesAndBurstsUseCase()
        val failed = duplicateResult is AppResult.Failure || nearDuplicateResult is AppResult.Failure
        return if (!failed) {
            Result.success()
        } else if (runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "hash_grouping_immediate"
        const val WORK_NAME_PERIODIC = "hash_grouping_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
