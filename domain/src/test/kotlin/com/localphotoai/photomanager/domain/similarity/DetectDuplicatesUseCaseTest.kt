package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectDuplicatesUseCaseTest {

    @Test
    fun `groups photos sharing a content hash and persists via the repository`() = runBlocking {
        val hashes = listOf(
            PhotoHashInput(1L, "same", 0L, null),
            PhotoHashInput(2L, "same", 0L, null),
            PhotoHashInput(3L, "unique", 0L, null),
        )
        var saved: Map<String, List<Long>>? = null
        val repository = object : NoOpPhotoGroupRepository() {
            override suspend fun fetchAllHashes(): List<PhotoHashInput> = hashes
            override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {
                saved = photoIdGroupsByHash
            }
        }

        val result = DetectDuplicatesUseCase(repository, NoOpLogger())()

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).value)
        assertEquals(setOf(1L, 2L), saved?.getValue("same")?.toSet())
    }

    @Test
    fun `no duplicates produces an empty group set`() = runBlocking {
        val hashes = listOf(PhotoHashInput(1L, "a", 0L, null), PhotoHashInput(2L, "b", 0L, null))
        var saved: Map<String, List<Long>>? = null
        val repository = object : NoOpPhotoGroupRepository() {
            override suspend fun fetchAllHashes(): List<PhotoHashInput> = hashes
            override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {
                saved = photoIdGroupsByHash
            }
        }

        val result = DetectDuplicatesUseCase(repository, NoOpLogger())()

        assertEquals(0, (result as AppResult.Success).value)
        assertTrue(saved?.isEmpty() == true)
    }
}
