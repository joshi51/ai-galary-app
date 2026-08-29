package com.localphotoai.photomanager.di

import android.util.Log
import com.localphotoai.photomanager.core.common.Logger
import javax.inject.Inject

/** [Logger] implementation backed by [android.util.Log]. The only Android-specific logger impl. */
class AndroidLogger @Inject constructor() : Logger {
    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}
