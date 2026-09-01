package com.localphotoai.photomanager.domain.testutil

import com.localphotoai.photomanager.core.common.Logger

class NoOpLogger : Logger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}
