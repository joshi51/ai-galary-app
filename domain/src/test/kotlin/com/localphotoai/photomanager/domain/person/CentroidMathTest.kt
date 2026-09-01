package com.localphotoai.photomanager.domain.person

import com.localphotoai.photomanager.domain.face.l2Normalize
import org.junit.Assert.assertEquals
import org.junit.Test

class CentroidMathTest {

    @Test
    fun `addVector sums element-wise`() {
        val result = addVector(floatArrayOf(1f, 2f, 3f), floatArrayOf(10f, 20f, 30f))

        assertEquals(11f, result[0], 1e-6f)
        assertEquals(22f, result[1], 1e-6f)
        assertEquals(33f, result[2], 1e-6f)
    }

    @Test
    fun `subtractVector reverses addVector exactly`() {
        val original = floatArrayOf(0.3f, 0.7f, -0.2f)
        val added = addVector(original, floatArrayOf(0.1f, -0.4f, 0.9f))

        val restored = subtractVector(added, floatArrayOf(0.1f, -0.4f, 0.9f))

        assertEquals(original[0], restored[0], 1e-6f)
        assertEquals(original[1], restored[1], 1e-6f)
        assertEquals(original[2], restored[2], 1e-6f)
    }

    @Test
    fun `cosineSimilarity of identical unit vectors is 1`() {
        val v = l2Normalize(floatArrayOf(1f, 2f, 3f))

        assertEquals(1f, cosineSimilarity(v, v), 1e-5f)
    }

    @Test
    fun `cosineSimilarity of orthogonal unit vectors is 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)

        assertEquals(0f, cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `normalizing a sum gives the same direction whether or not divided by count`() {
        val sum = floatArrayOf(3f, 4f) // e.g. two identical (1.5, 2) vectors summed
        val average = floatArrayOf(1.5f, 2f)

        val normalizedSum = l2Normalize(sum)
        val normalizedAverage = l2Normalize(average)

        assertEquals(normalizedAverage[0], normalizedSum[0], 1e-5f)
        assertEquals(normalizedAverage[1], normalizedSum[1], 1e-5f)
    }
}
