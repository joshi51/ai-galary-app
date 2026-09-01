package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Composite `(photoId, groupId)` primary key — deliberately NOT `photoId` alone. A photo can
 * legitimately belong to groups of *different* kinds at once (e.g. both a NEAR_DUPLICATE group
 * and a VISUALLY_SIMILAR group), since [SimilarGroupEntity.kind] lives on the group, not the
 * membership row, and this table is shared across all three kinds. Keying on `photoId` alone was
 * a real bug found during Phase 7's on-device verification: the visually-similar pass's upsert
 * silently evicted a photo's near-duplicate/burst membership (and vice versa) whenever the same
 * photo qualified for both, because both passes wrote to the same single-row-per-photo slot.
 * Within one kind, a photo still belongs to at most one group — that's enforced by each
 * grouping pass's own logic (full wipe-and-rebuild for near-duplicate/burst, "already grouped"
 * exclusion for the incremental visually-similar pass — see `PhotoGroupRepositoryImpl`), not by
 * this schema.
 */
@Entity(
    tableName = "similar_group_members",
    primaryKeys = ["photoId", "groupId"],
    foreignKeys = [
        ForeignKey(entity = SimilarGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("groupId"), Index("photoId")],
)
data class SimilarGroupMemberEntity(
    val photoId: Long,
    val groupId: Long,
    val similarityToRepresentative: Float,
)
