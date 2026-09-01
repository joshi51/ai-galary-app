package com.localphotoai.photomanager.domain.face

/**
 * A face's embedding vector from the current (or a past) embedding model version. [vector] is
 * L2-normalized so cosine similarity between two embeddings reduces to a plain dot product.
 *
 * Manual [equals]/[hashCode] are required because Kotlin data classes compare array properties
 * by reference, not content — without this, two embeddings with identical vectors would compare
 * unequal, which would silently break tests and any future duplicate/similarity comparisons.
 */
class FaceEmbedding(
    val faceId: Long,
    val modelVersion: Int,
    val vector: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbedding) return false
        return faceId == other.faceId && modelVersion == other.modelVersion && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + modelVersion
        result = 31 * result + vector.contentHashCode()
        return result
    }

    override fun toString(): String =
        "FaceEmbedding(faceId=$faceId, modelVersion=$modelVersion, vector=${vector.size} floats)"
}

/** L2-normalizes [vector] in place semantics (returns a new array), so its Euclidean norm is 1. */
fun l2Normalize(vector: FloatArray): FloatArray {
    var sumOfSquares = 0.0
    for (value in vector) sumOfSquares += value.toDouble() * value.toDouble()
    val norm = kotlin.math.sqrt(sumOfSquares)
    if (norm == 0.0) return vector.copyOf()
    return FloatArray(vector.size) { i -> (vector[i] / norm).toFloat() }
}
