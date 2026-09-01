package com.localphotoai.photomanager.domain.similarity

/**
 * dHash (difference hash): given a 9x8 grayscale pixel array (row-major, 72 values), compares
 * each pixel to its right neighbor to produce a 64-bit hash. Similarity between two photos is
 * the Hamming distance between their hashes — 0 means identical, 64 means maximally different.
 * A public-domain algorithm, no license or model needed.
 */
object PerceptualHashCalculator {

    const val HASH_WIDTH = 9
    const val HASH_HEIGHT = 8

    fun dHash(grayscalePixels: IntArray): Long {
        require(grayscalePixels.size == HASH_WIDTH * HASH_HEIGHT) {
            "Expected ${HASH_WIDTH * HASH_HEIGHT} pixels, got ${grayscalePixels.size}"
        }
        var hash = 0L
        var bitIndex = 0
        for (row in 0 until HASH_HEIGHT) {
            for (col in 0 until HASH_WIDTH - 1) {
                val left = grayscalePixels[row * HASH_WIDTH + col]
                val right = grayscalePixels[row * HASH_WIDTH + col + 1]
                if (left < right) hash = hash or (1L shl bitIndex)
                bitIndex++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
