package com.localphotoai.photomanager.core.common

/**
 * Result wrapper for operations that can fail with a typed [AppError], used across
 * `:domain` use cases and repository interfaces instead of throwing exceptions.
 */
sealed class AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>()
    data class Failure(val error: AppError) : AppResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): AppResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (AppError) -> Unit): AppResult<T> {
        if (this is Failure) action(error)
        return this
    }
}
