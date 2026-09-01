package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.face.DetectFacesUseCase
import com.localphotoai.photomanager.domain.face.EmbeddingScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one face-detection pass via [DetectFacesUseCase]. Safe to interrupt and re-run: each run
 * re-queries for photos still missing detection, so a job killed mid-run resumes cleanly on the
 * next trigger without redoing already-committed photos. On success, triggers an immediate
 * embedding-generation pass so newly detected faces get processed without waiting for
 * embedding's own periodic reconciliation job.
 */
@HiltWorker
class FaceDetectionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val detectFacesUseCase: DetectFacesUseCase,
    private val embeddingScheduler: EmbeddingScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = detectFacesUseCase()
        return when (result) {
            is AppResult.Success -> {
                embeddingScheduler.scheduleImmediateEmbedding()
                Result.success()
            }
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "face_detection_immediate"
        const val WORK_NAME_PERIODIC = "face_detection_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
