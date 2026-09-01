package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [photoId] is the primary key — a photo belongs to at most one duplicate group at a time, the
 * same single-ownership-via-primary-key simplification [PersonFaceEntity] uses: re-grouping
 * naturally supersedes stale membership rather than needing explicit cleanup.
 */
@Entity(
    tableName = "duplicate_group_members",
    foreignKeys = [
        ForeignKey(entity = DuplicateGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("groupId")],
)
data class DuplicateGroupMemberEntity(
    @PrimaryKey val photoId: Long,
    val groupId: Long,
)
