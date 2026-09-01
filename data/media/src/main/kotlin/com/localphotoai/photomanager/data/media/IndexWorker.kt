package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.face.FaceDetectionScheduler
import com.localphotoai.photomanager.domain.photo.IndexPhotosUseCase
import com.localphotoai.photomanager.domain.similarity.HashScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one indexing pass via [IndexPhotosUseCase]. Safe to interrupt and re-run: the use case
 * re-diffs from scratch each time and only re-applies what's still outstanding, so a job killed
 * mid-run resumes cleanly on the next trigger without redoing already-committed work. On success,
 * triggers an immediate face-detection pass and an immediate hashing pass (Phase 7) — two
 * independent branches, since hashing doesn't depend on face detection.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val indexPhotosUseCase: IndexPhotosUseCase,
    private val faceDetectionScheduler: FaceDetectionScheduler,
    private val hashScheduler: HashScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = indexPhotosUseCase()
        return when (result) {
            is AppResult.Success -> {
                faceDetectionScheduler.scheduleImmediateDetection()
                hashScheduler.scheduleImmediateHashing()
                Result.success()
            }
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "photo_index_immediate"
        const val WORK_NAME_PERIODIC = "photo_index_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
