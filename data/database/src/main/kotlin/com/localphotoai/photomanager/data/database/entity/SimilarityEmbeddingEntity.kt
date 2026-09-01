package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The current whole-photo embedding used for visual-similarity grouping. [photoId] is the
 * primary key — a photo has at most one (current-version) similarity embedding, replaced
 * wholesale on regeneration, mirroring [EmbeddingEntity]'s cardinality for faces.
 */
@Entity(
    tableName = "similarity_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["mediaStoreId"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SimilarityEmbeddingEntity(
    @PrimaryKey val photoId: Long,
    val modelVersion: Int,
    val vector: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimilarityEmbeddingEntity) return false
        return photoId == other.photoId && modelVersion == other.modelVersion && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = photoId.hashCode()
        result = 31 * result + modelVersion
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
