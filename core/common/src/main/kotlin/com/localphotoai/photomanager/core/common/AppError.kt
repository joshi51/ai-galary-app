package com.localphotoai.photomanager.core.common

/**
 * Typed error hierarchy for domain/data operations, so failures carry structured
 * information instead of raw exceptions leaking into the presentation layer.
 */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {
    data class Io(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class NotFound(override val message: String) : AppError(message)
    data class Validation(override val message: String) : AppError(message)
    data class Unknown(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
}
