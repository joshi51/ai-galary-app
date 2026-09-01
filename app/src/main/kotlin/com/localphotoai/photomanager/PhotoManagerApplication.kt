package com.localphotoai.photomanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.localphotoai.photomanager.core.common.Logger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

private const val TAG = "PhotoManagerApplication"

@HiltAndroidApp
class PhotoManagerApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installUncaughtExceptionLogger()
        logger.info(TAG, "Application started")
    }

    /**
     * Logs uncaught exceptions before handing off to the platform's default handler,
     * so a crash is always recorded rather than only surfacing as a silent process death.
     */
    private fun installUncaughtExceptionLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logger.error(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
