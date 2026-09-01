package com.localphotoai.photomanager.domain.similarity

/** One photo's stored hashes, as needed for grouping — independent of the Room entity shape. */
data class PhotoHashInput(
    val photoId: Long,
    val contentHash: String,
    val perceptualHash: Long,
    val dateTakenMs: Long?,
)

enum class SimilarGroupKindResult { NEAR_DUPLICATE, BURST }

/** Groups photos sharing an identical [PhotoHashInput.contentHash]. Singleton "groups" are dropped. */
fun groupByExactHash(hashes: List<PhotoHashInput>): Map<String, List<Long>> =
    hashes.groupBy { it.contentHash }
        .filterValues { it.size >= 2 }
        .mapValues { (_, group) -> group.map { it.photoId } }

/**
 * Groups photos whose perceptual hashes are within [hammingThreshold] of each other into
 * near-duplicate or burst groups (union-find over the pairwise-similar graph, since "A is near B"
 * and "B is near C" should join A/B/C into one group even if A and C aren't directly close). A
 * group is BURST if every member's [PhotoHashInput.dateTakenMs] is within [burstWindowMs] of at
 * least one other member's; otherwise NEAR_DUPLICATE. A null `dateTakenMs` never counts toward a
 * burst window (unknown timing can't prove temporal proximity), so such a group is NEAR_DUPLICATE
 * at most. [hammingThreshold]/[burstWindowMs] are named, documented, untuned heuristics — same
 * honest treatment as [com.localphotoai.photomanager.domain.person.FaceClusterer]'s threshold.
 */
fun groupNearDuplicatesAndBursts(
    hashes: List<PhotoHashInput>,
    hammingThreshold: Int,
    burstWindowMs: Long,
): List<Pair<SimilarGroupKindResult, List<Long>>> {
    val parent = hashes.associate { it.photoId to it.photoId }.toMutableMap()

    fun find(id: Long): Long {
        var root = id
        while (parent.getValue(root) != root) root = parent.getValue(root)
        return root
    }

    fun union(a: Long, b: Long) {
        val rootA = find(a)
        val rootB = find(b)
        if (rootA != rootB) parent[rootA] = rootB
    }

    for (i in hashes.indices) {
        for (j in i + 1 until hashes.size) {
            val distance = PerceptualHashCalculator.hammingDistance(hashes[i].perceptualHash, hashes[j].perceptualHash)
            if (distance <= hammingThreshold) union(hashes[i].photoId, hashes[j].photoId)
        }
    }

    val byId = hashes.associateBy { it.photoId }
    val groups = hashes.map { it.photoId }.groupBy { find(it) }.values.filter { it.size >= 2 }

    return groups.map { photoIds ->
        val members = photoIds.map { byId.getValue(it) }
        val isBurst = members.all { m ->
            m.dateTakenMs != null && members.any { other ->
                other.photoId != m.photoId && other.dateTakenMs != null &&
                    kotlin.math.abs(other.dateTakenMs - m.dateTakenMs) <= burstWindowMs
            }
        }
        (if (isBurst) SimilarGroupKindResult.BURST else SimilarGroupKindResult.NEAR_DUPLICATE) to photoIds
    }
}
