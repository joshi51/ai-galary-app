package com.localphotoai.photomanager.domain.face

/**
 * Generates a normalized face embedding vector from a face region within a photo. Implemented in
 * `:ml:embeddings` on top of a local TFLite model — embeddings never leave the device.
 *
 * [modelVersion] identifies the current model: bumping it is how a future model swap triggers
 * regeneration of every existing embedding (see [FaceEmbeddingRepository.fetchFacesNeedingEmbedding]).
 */
interface EmbeddingGenerator {

    val modelVersion: Int

    /** Whether the model file is present and ready to run — false if it hasn't been downloaded yet. */
    suspend fun isReady(): Boolean

    /**
     * Crops the face region out of the photo at [photoUri] (using [left]/[top]/[right]/[bottom],
     * normalized `[0,1]` in the detector's coordinate space, with [orientationDegrees] applied),
     * aligns/resizes it to the model's input size, and runs inference.
     *
     * Throws if the model isn't ready or the crop/decode fails — callers are expected to catch
     * per-face so one bad face never aborts a batch.
     */
    suspend fun generateEmbedding(
        photoUri: String,
        photoWidthPx: Int,
        photoHeightPx: Int,
        orientationDegrees: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): FloatArray
}
