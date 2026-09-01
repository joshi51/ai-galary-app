package com.localphotoai.photomanager.domain.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FaceEmbeddingTest {

    @Test
    fun `l2Normalize produces a unit-norm vector`() {
        val vector = floatArrayOf(3f, 4f)

        val normalized = l2Normalize(vector)

        assertEquals(0.6f, normalized[0], 1e-5f)
        assertEquals(0.8f, normalized[1], 1e-5f)
        val norm = sqrt(normalized[0] * normalized[0] + normalized[1] * normalized[1])
        assertEquals(1.0f, norm, 1e-5f)
    }

    @Test
    fun `l2Normalize of an all-zero vector returns a copy without dividing by zero`() {
        val vector = floatArrayOf(0f, 0f, 0f)

        val normalized = l2Normalize(vector)

        assertEquals(0f, normalized[0])
        assertEquals(0f, normalized[1])
        assertEquals(0f, normalized[2])
    }

    @Test
    fun `l2Normalize does not mutate the input array`() {
        val vector = floatArrayOf(1f, 2f, 3f)
        val original = vector.copyOf()

        l2Normalize(vector)

        assertTrue(vector.contentEquals(original))
    }

    @Test
    fun `FaceEmbedding equality compares vector contents, not array identity`() {
        val a = FaceEmbedding(faceId = 1L, modelVersion = 1, vector = floatArrayOf(0.1f, 0.2f))
        val b = FaceEmbedding(faceId = 1L, modelVersion = 1, vector = floatArrayOf(0.1f, 0.2f))
        val c = FaceEmbedding(faceId = 1L, modelVersion = 1, vector = floatArrayOf(0.9f, 0.2f))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
