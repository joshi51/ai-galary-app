package com.localphotoai.photomanager.core.common

/**
 * Logging abstraction so `:domain` and other pure-Kotlin modules never depend on
 * `android.util.Log` directly. Concrete implementations live in platform-aware modules.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}
