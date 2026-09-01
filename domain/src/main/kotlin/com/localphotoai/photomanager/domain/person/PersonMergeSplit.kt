package com.localphotoai.photomanager.domain.person

/** Result of merging [sourcePersonId] into [targetPersonId]: the target keeps growing, the source is deleted. */
data class MergeOutcome(val resultingName: String?, val targetPersonId: Long, val sourcePersonIdToDelete: Long)

/**
 * Decides the merged person's name: if the target is already named, that name wins (it's the
 * person the user chose to merge *into*); otherwise the source's name (if any) carries over, so
 * a merge never silently discards a name either person already had.
 */
fun planMerge(sourcePersonId: Long, sourceName: String?, targetPersonId: Long, targetName: String?): MergeOutcome =
    MergeOutcome(
        resultingName = targetName ?: sourceName,
        targetPersonId = targetPersonId,
        sourcePersonIdToDelete = sourcePersonId,
    )

/** Whether removing a face leaves a person with no members left, and should be cleaned up. */
fun shouldDeletePersonAfterRemoval(remainingFaceCount: Int): Boolean = remainingFaceCount <= 0
