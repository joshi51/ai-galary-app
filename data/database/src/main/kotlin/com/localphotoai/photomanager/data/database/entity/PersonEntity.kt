package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cluster of faces believed to be the same person — provisional by design (see
 * ARCHITECTURE.md's Phase 5 notes). [centroidSum] is the raw element-wise sum of member face
 * embeddings, not a true average — see `CentroidMath.kt` in `:domain` for why summing (rather
 * than averaging) makes membership changes exact and cheap. [name] stays null until the user
 * explicitly names this person; clustering never assigns names automatically.
 */
@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?,
    val representativeFaceId: Long?,
    val createdAt: Long,
    val clusterAlgoVersion: Int,
    val centroidSum: ByteArray,
    val memberCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonEntity) return false
        return id == other.id && name == other.name && representativeFaceId == other.representativeFaceId &&
            createdAt == other.createdAt && clusterAlgoVersion == other.clusterAlgoVersion &&
            memberCount == other.memberCount && centroidSum.contentEquals(other.centroidSum)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (representativeFaceId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + clusterAlgoVersion
        result = 31 * result + memberCount
        result = 31 * result + centroidSum.contentHashCode()
        return result
    }
}
