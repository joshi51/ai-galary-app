package com.localphotoai.photomanager.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Injectable coroutine dispatcher set, so use cases and repositories never reference
 * [kotlinx.coroutines.Dispatchers] directly and stay swappable in tests.
 */
interface AppDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}
