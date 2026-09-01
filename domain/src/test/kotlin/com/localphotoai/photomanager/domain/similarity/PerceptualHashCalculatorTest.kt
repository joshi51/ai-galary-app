package com.localphotoai.photomanager.domain.similarity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashCalculatorTest {

    @Test
    fun `identical pixel arrays produce the same hash and zero Hamming distance`() {
        val pixels = IntArray(72) { it * 3 }
        val a = PerceptualHashCalculator.dHash(pixels)
        val b = PerceptualHashCalculator.dHash(pixels)
        assertEquals(a, b)
        assertEquals(0, PerceptualHashCalculator.hammingDistance(a, b))
    }

    @Test
    fun `a strictly increasing gradient produces a hash of all-set bits`() {
        // dHash compares each pixel to its right neighbor; a strictly increasing row means
        // every comparison is "brighter than the left neighbor" -> every bit set to 1.
        val pixels = IntArray(72) { it }
        val hash = PerceptualHashCalculator.dHash(pixels)
        assertEquals(-1L, hash) // all 64 bits set
    }

    @Test
    fun `Hamming distance is symmetric`() {
        val a = PerceptualHashCalculator.dHash(IntArray(72) { it })
        val b = PerceptualHashCalculator.dHash(IntArray(72) { 71 - it })
        assertEquals(PerceptualHashCalculator.hammingDistance(a, b), PerceptualHashCalculator.hammingDistance(b, a))
    }

    @Test
    fun `maximally different hashes have Hamming distance 64`() {
        assertEquals(64, PerceptualHashCalculator.hammingDistance(0L, -1L))
    }
}
