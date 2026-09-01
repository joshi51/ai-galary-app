package com.localphotoai.photomanager.data.media

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.localphotoai.photomanager.domain.photo.IndexingScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules WorkManager indexing jobs and watches MediaStore for changes, triggering an
 * immediate (debounced) re-index so newly added or deleted photos are picked up without waiting
 * for the periodic reconciliation job. Debouncing relies on [scheduleImmediateIndex]'s `KEEP`
 * unique-work policy — a burst of change notifications collapses into the single
 * already-queued/running job.
 */
@Singleton
class IndexingSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : IndexingScheduler {

    private var observerRegistered = false

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleImmediateIndex()
        }
    }

    override fun scheduleImmediateIndex() {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IndexWorker.WORK_NAME_IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleIncrementalIndexing() {
        registerContentObserver()
        val request = PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS)
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(IndexWorker.WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun registerContentObserver() {
        if (observerRegistered) return
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver,
        )
        observerRegistered = true
    }
}
