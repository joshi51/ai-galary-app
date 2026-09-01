package com.localphotoai.photomanager.domain.person

import com.localphotoai.photomanager.domain.face.l2Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun unit(x: Float, y: Float) = l2Normalize(floatArrayOf(x, y))

private fun face(id: Long, x: Float, y: Float) = FaceEmbeddingForClustering(id, unit(x, y))

class FaceClustererTest {

    @Test
    fun `empty input produces an empty result`() {
        val result = FaceClusterer.cluster(emptyList(), emptyList())

        assertTrue(result.outcomes.isEmpty())
        assertEquals(0, result.newClusterCount)
    }

    @Test
    fun `two faces pointing the same direction form one new cluster together`() {
        val a = face(1L, 1f, 0f)
        val b = face(2L, 0.99f, 0.01f) // nearly identical direction

        val result = FaceClusterer.cluster(listOf(a, b), emptyList())

        assertEquals(1, result.newClusterCount)
        val outcomes = result.outcomes.filterIsInstance<ClusterOutcome.AssignedToNewCluster>()
        assertEquals(2, outcomes.size)
        assertEquals(outcomes[0].newClusterIndex, outcomes[1].newClusterIndex)
    }

    @Test
    fun `two faces pointing in very different directions form separate new clusters`() {
        val a = face(1L, 1f, 0f)
        val b = face(2L, 0f, 1f) // orthogonal — cosine similarity 0, well below threshold

        val result = FaceClusterer.cluster(listOf(a, b), emptyList())

        assertEquals(2, result.newClusterCount)
        val outcomes = result.outcomes.filterIsInstance<ClusterOutcome.AssignedToNewCluster>()
        assertTrue(outcomes[0].newClusterIndex != outcomes[1].newClusterIndex)
    }

    @Test
    fun `a face matching an existing cluster is assigned to that person, not a new cluster`() {
        val existing = ExistingClusterCentroid(personId = 42L, centroidSum = unit(1f, 0f))
        val matching = face(1L, 0.98f, 0.02f)

        val result = FaceClusterer.cluster(listOf(matching), listOf(existing))

        assertEquals(0, result.newClusterCount)
        val outcome = result.outcomes.single() as ClusterOutcome.AssignedToExisting
        assertEquals(42L, outcome.personId)
        assertTrue(outcome.confidence >= FaceClusterer.DEFAULT_SIMILARITY_THRESHOLD)
    }

    @Test
    fun `a face far from every existing cluster seeds a new cluster instead of forcing a match`() {
        val existing = ExistingClusterCentroid(personId = 42L, centroidSum = unit(1f, 0f))
        val dissimilar = face(1L, 0f, 1f)

        val result = FaceClusterer.cluster(listOf(dissimilar), listOf(existing))

        assertEquals(1, result.newClusterCount)
        val outcome = result.outcomes.single() as ClusterOutcome.AssignedToNewCluster
        assertEquals(0, outcome.newClusterIndex)
    }

    @Test
    fun `mixed batch assigns matching faces to existing people and groups the rest into new clusters`() {
        val existingA = ExistingClusterCentroid(personId = 1L, centroidSum = unit(1f, 0f))
        val existingB = ExistingClusterCentroid(personId = 2L, centroidSum = unit(-1f, 0f))
        val matchesA = face(10L, 0.97f, 0.03f)
        val matchesB = face(11L, -0.97f, 0.03f)
        val newFace1 = face(12L, 0f, 1f)
        val newFace2 = face(13L, 0.01f, 0.99f)

        val result = FaceClusterer.cluster(
            faces = listOf(matchesA, matchesB, newFace1, newFace2),
            existingClusters = listOf(existingA, existingB),
        )

        val byFaceId = result.outcomes.associateBy { it.faceId }
        assertEquals(1L, (byFaceId.getValue(10L) as ClusterOutcome.AssignedToExisting).personId)
        assertEquals(2L, (byFaceId.getValue(11L) as ClusterOutcome.AssignedToExisting).personId)
        val newCluster1 = byFaceId.getValue(12L) as ClusterOutcome.AssignedToNewCluster
        val newCluster2 = byFaceId.getValue(13L) as ClusterOutcome.AssignedToNewCluster
        assertEquals(newCluster1.newClusterIndex, newCluster2.newClusterIndex)
        assertEquals(1, result.newClusterCount)
    }

    @Test
    fun `a stricter threshold requires closer similarity before assigning to an existing cluster`() {
        val existing = ExistingClusterCentroid(personId = 42L, centroidSum = unit(1f, 0f))
        val looselyMatching = face(1L, 0.9f, 0.44f) // cosine similarity ~0.9, above default 0.6 but not near 1

        val lenientResult = FaceClusterer.cluster(listOf(looselyMatching), listOf(existing), similarityThreshold = 0.6f)
        val strictResult = FaceClusterer.cluster(listOf(looselyMatching), listOf(existing), similarityThreshold = 0.99f)

        assertTrue(lenientResult.outcomes.single() is ClusterOutcome.AssignedToExisting)
        assertTrue(strictResult.outcomes.single() is ClusterOutcome.AssignedToNewCluster)
    }

    @Test
    fun `a new cluster grows as more matching faces arrive and can then out-compete the original centroid direction`() {
        // Three faces close to (1,0) followed by one close to (0.9, 0.44) — the growing cluster's
        // centroid should still be closest for the near-(1,0) faces, verifying centroid updates
        // take effect within a single clustering call, not just across separate runs.
        val faces = listOf(
            face(1L, 1f, 0f),
            face(2L, 0.99f, 0.01f),
            face(3L, 0.98f, 0.02f),
        )

        val result = FaceClusterer.cluster(faces, emptyList())

        val indices = result.outcomes.filterIsInstance<ClusterOutcome.AssignedToNewCluster>().map { it.newClusterIndex }
        assertTrue(indices.all { it == indices.first() })
    }
}
