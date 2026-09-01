package com.localphotoai.photomanager.llm.runtime

internal object NativeLlamaBridge {
    init {
        System.loadLibrary("llm_jni")
    }

    external fun nativeLoadModel(modelPath: String, contextSize: Int): Long
    external fun nativeGenerateWithGrammar(handle: Long, prompt: String, grammar: String, maxTokens: Int): String
    external fun nativeFreeModel(handle: Long)
}
