package com.localphotoai.photomanager.domain.similarity

/** Computes a photo's content (SHA-256) and perceptual (dHash) hashes. Implemented in `:data:media`. */
interface PhotoHasher {
    suspend fun hash(photoUri: String): PhotoHashResult
}

data class PhotoHashResult(val contentHash: String, val perceptualHash: Long)
