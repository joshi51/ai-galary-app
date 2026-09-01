package com.localphotoai.photomanager.llm.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelFileStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    val modelFile: File get() = File(modelsDir, Llama32ModelSpec.FILENAME)

    fun tempFile(): File = File(modelsDir, "${Llama32ModelSpec.FILENAME}.download")

    fun isModelPresent(): Boolean = modelFile.exists() && modelFile.length() > 0
}
