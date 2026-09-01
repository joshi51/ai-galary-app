package com.localphotoai.photomanager.domain.similarity

/**
 * Generates a normalized whole-photo embedding for visual-similarity grouping. Implemented in
 * `:ml:embeddings` on top of a bundled (not downloaded) TFLite MobileNetV3-Small model — see
 * ARCHITECTURE.md's Phase 7 notes for why this model is bundled rather than downloaded.
 */
interface ImageSimilarityEmbeddingGenerator {
    val modelVersion: Int
    suspend fun generateEmbedding(photoUri: String, widthPx: Int, heightPx: Int, orientationDegrees: Int): FloatArray
}
