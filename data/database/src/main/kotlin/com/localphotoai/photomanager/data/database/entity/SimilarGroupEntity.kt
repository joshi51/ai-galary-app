package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SimilarGroupKind { NEAR_DUPLICATE, BURST, VISUALLY_SIMILAR }

/**
 * A group of photos that are similar but not byte-identical. [kind] distinguishes near-duplicate
 * (perceptual-hash match), burst (near-duplicate + taken moments apart), and visually-similar
 * (broader, embedding-based) groups within one shape, per the design spec's decision to avoid a
 * third near-identical table pair.
 */
@Entity(tableName = "similar_groups")
data class SimilarGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SimilarGroupKind,
    val avgSimilarity: Float,
)
