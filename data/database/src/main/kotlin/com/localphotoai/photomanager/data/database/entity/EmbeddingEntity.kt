package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The current embedding vector for one face. [faceId] is the primary key — a face has at most
 * one (current-version) embedding, replaced wholesale on regeneration rather than versioned as
 * history, matching the "has (current version)" cardinality in ARCHITECTURE.md §16. [vector] is
 * stored as raw float32 bytes (128 floats × 4 bytes = 512 bytes/face) rather than JSON/text, for
 * compact storage and fast (de)serialization.
 */
@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = FaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["faceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EmbeddingEntity(
    @PrimaryKey val faceId: Long,
    val modelVersion: Int,
    val vector: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return faceId == other.faceId && modelVersion == other.modelVersion && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + modelVersion
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
