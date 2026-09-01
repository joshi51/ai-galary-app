package com.localphotoai.photomanager.domain.clustering

import com.localphotoai.photomanager.domain.face.l2Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun unit(x: Float, y: Float) = l2Normalize(floatArrayOf(x, y))
private fun item(id: Long, x: Float, y: Float) = EmbeddingForClustering(id, unit(x, y))

class NearestCentroidClustererTest {

    @Test
    fun `empty input produces an empty result`() {
        val result = NearestCentroidClusterer.cluster(emptyList(), emptyList(), similarityThreshold = 0.6f)
        assertTrue(result.assignments.isEmpty())
        assertEquals(0, result.newClusterCount)
    }

    @Test
    fun `two items pointing the same direction form one new cluster together`() {
        val a = item(1L, 1f, 0f)
        val b = item(2L, 0.99f, 0.01f)
        val result = NearestCentroidClusterer.cluster(listOf(a, b), emptyList(), similarityThreshold = 0.6f)
        assertEquals(1, result.newClusterCount)
        val assignments = result.assignments.filterIsInstance<ClusterAssignment.ToNew>()
        assertEquals(2, assignments.size)
        assertEquals(assignments[0].newClusterIndex, assignments[1].newClusterIndex)
    }

    @Test
    fun `two items pointing in very different directions form separate new clusters`() {
        val a = item(1L, 1f, 0f)
        val b = item(2L, 0f, 1f)
        val result = NearestCentroidClusterer.cluster(listOf(a, b), emptyList(), similarityThreshold = 0.6f)
        assertEquals(2, result.newClusterCount)
        val assignments = result.assignments.filterIsInstance<ClusterAssignment.ToNew>()
        assertTrue(assignments[0].newClusterIndex != assignments[1].newClusterIndex)
    }

    @Test
    fun `an item matching an existing cluster is assigned to it, not a new cluster`() {
        val existing = ExistingCentroid(groupId = 42L, centroidSum = unit(1f, 0f))
        val matching = item(1L, 0.98f, 0.02f)
        val result = NearestCentroidClusterer.cluster(listOf(matching), listOf(existing), similarityThreshold = 0.6f)
        assertEquals(0, result.newClusterCount)
        val assignment = result.assignments.single() as ClusterAssignment.ToExisting
        assertEquals(42L, assignment.groupId)
        assertTrue(assignment.confidence >= 0.6f)
    }

    @Test
    fun `a stricter threshold requires closer similarity before assigning to an existing cluster`() {
        val existing = ExistingCentroid(groupId = 42L, centroidSum = unit(1f, 0f))
        val looselyMatching = item(1L, 0.9f, 0.44f)
        val lenient = NearestCentroidClusterer.cluster(listOf(looselyMatching), listOf(existing), similarityThreshold = 0.6f)
        val strict = NearestCentroidClusterer.cluster(listOf(looselyMatching), listOf(existing), similarityThreshold = 0.99f)
        assertTrue(lenient.assignments.single() is ClusterAssignment.ToExisting)
        assertTrue(strict.assignments.single() is ClusterAssignment.ToNew)
    }
}
