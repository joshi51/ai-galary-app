package com.localphotoai.photomanager.domain.statistics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeStorageStatisticsRepository(private val stats: StorageStatistics) : StorageStatisticsRepository {
    override suspend fun fetchStatistics(): StorageStatistics = stats
}

class GetStorageStatisticsUseCaseTest {

    @Test
    fun `returns exactly what the repository provides`() = runBlocking {
        val stats = StorageStatistics(
            photoCount = 328,
            totalSizeBytes = 1_200_000_000L,
            peopleCount = 5,
            faceCount = 12,
            duplicateGroupCount = 3,
            similarGroupCount = 7,
        )
        val useCase = GetStorageStatisticsUseCase(FakeStorageStatisticsRepository(stats))

        val result = useCase()

        assertEquals(stats, result)
    }
}
