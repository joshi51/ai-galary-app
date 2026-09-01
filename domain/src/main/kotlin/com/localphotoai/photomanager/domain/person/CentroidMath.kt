package com.localphotoai.photomanager.domain.person

/**
 * A cluster's centroid is tracked as a raw element-wise sum of its member embeddings, never a
 * true average — L2-normalization is scale-invariant, so `l2Normalize(sum)` gives the exact same
 * direction as `l2Normalize(sum / count)` without ever needing to divide. This makes membership
 * changes exact and cheap: adding a face is `sum += vector`, removing one is `sum -= vector`,
 * with no rounding drift from repeated re-averaging.
 */
fun addVector(sum: FloatArray, vector: FloatArray): FloatArray = FloatArray(sum.size) { sum[it] + vector[it] }

fun subtractVector(sum: FloatArray, vector: FloatArray): FloatArray = FloatArray(sum.size) { sum[it] - vector[it] }

/** Cosine similarity of two vectors already known to be unit-length (a plain dot product). */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}
