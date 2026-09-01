package com.localphotoai.photomanager.ml.embeddings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The face-embedding model file's expected identity, per §4/§18 of ARCHITECTURE.md — see also
 * docs/ARCHITECTURE.md's Phase 4 notes for the license/provenance writeup. */
object FaceNetModelSpec {
    const val MODEL_VERSION = 1
    const val FILENAME = "facenet_v1.tflite"
    const val DOWNLOAD_URL =
        "https://raw.githubusercontent.com/shubham0204/FaceRecognition_With_FaceNet_Android/master/app/src/main/assets/facenet.tflite"
    const val SHA256 = "d7c1f7f130376982c7004920ddc41925ac2e5aecf6522f476c8bbb3669db7013"
    const val INPUT_SIZE = 160
    const val OUTPUT_SIZE = 128
}

/** Resolves the face-embedding model's location in app-private storage (never shared storage). */
@Singleton
class ModelFileStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    val modelFile: File get() = File(modelsDir, FaceNetModelSpec.FILENAME)

    fun tempFile(): File = File(modelsDir, "${FaceNetModelSpec.FILENAME}.download")

    fun isModelPresent(): Boolean = modelFile.exists() && modelFile.length() > 0
}
