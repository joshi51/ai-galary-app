package com.localphotoai.photomanager.llm.runtime

import com.localphotoai.photomanager.core.common.AppDispatchers
import com.localphotoai.photomanager.domain.tool.LlmEngine
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_RESPONSE_TOKENS = 256

/**
 * Lazily loads the model on first use and serializes calls — native llama.cpp contexts aren't
 * safely shared across concurrent calls, per ARCHITECTURE.md §18. Model load and inference are
 * both blocking native (JNI) calls, so both run on [AppDispatchers.default] — never on whatever
 * dispatcher happened to call [generate] (typically `Dispatchers.Main` via `viewModelScope`),
 * which would otherwise block the UI thread for the full duration and trigger an ANR. This was
 * a real bug caught during Phase 8's on-device verification, not a hypothetical: the app was
 * killed by the system after an ANR the first time this ran without the `withContext` below.
 */
@Singleton
class LlamaCppEngine @Inject constructor(
    private val modelFileStore: ModelFileStore,
    private val dispatchers: AppDispatchers,
) : LlmEngine {

    private val mutex = Mutex()
    private val handle = AtomicLong(0)

    override suspend fun generate(prompt: String, grammar: String): String = withContext(dispatchers.default) {
        mutex.withLock {
            ensureLoaded()
            val currentHandle = handle.get()
            if (currentHandle == 0L) return@withLock ""
            NativeLlamaBridge.nativeGenerateWithGrammar(currentHandle, prompt, grammar, MAX_RESPONSE_TOKENS)
        }
    }

    private fun ensureLoaded() {
        if (handle.get() != 0L) return
        if (!modelFileStore.isModelPresent()) return
        handle.set(
            NativeLlamaBridge.nativeLoadModel(modelFileStore.modelFile.absolutePath, Llama32ModelSpec.CONTEXT_SIZE),
        )
    }
}
