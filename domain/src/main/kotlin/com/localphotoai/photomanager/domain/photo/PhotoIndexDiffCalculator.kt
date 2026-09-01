package com.localphotoai.photomanager.domain.photo

/** Result of diffing a fresh MediaStore snapshot against what's already indexed locally. */
data class PhotoIndexDiff(
    val newOrChangedIds: List<Long>,
    val deletedIds: List<Long>,
) {
    val isEmpty: Boolean get() = newOrChangedIds.isEmpty() && deletedIds.isEmpty()
}

/**
 * Pure diffing logic for incremental indexing: given what MediaStore reports now and what's
 * already stored locally, determines which photos are new/changed (need a full metadata
 * re-scan) and which were deleted (need removal). A photo is considered changed only when its
 * `dateModifiedMs` differs from the locally stored value, so untouched photos are never
 * rescanned.
 */
object PhotoIndexDiffCalculator {

    fun computeDiff(
        remote: List<LightPhotoRecord>,
        local: List<LightPhotoRecord>,
    ): PhotoIndexDiff {
        val localById = local.associateBy { it.mediaStoreId }
        val remoteIds = HashSet<Long>(remote.size)

        val newOrChanged = mutableListOf<Long>()
        for (record in remote) {
            remoteIds += record.mediaStoreId
            val existing = localById[record.mediaStoreId]
            if (existing == null || existing.dateModifiedMs != record.dateModifiedMs) {
                newOrChanged += record.mediaStoreId
            }
        }

        val deleted = local
            .filter { it.mediaStoreId !in remoteIds }
            .map { it.mediaStoreId }

        return PhotoIndexDiff(newOrChangedIds = newOrChanged, deletedIds = deleted)
    }
}
