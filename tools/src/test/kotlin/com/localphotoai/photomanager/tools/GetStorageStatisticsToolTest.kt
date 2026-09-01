package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase
import com.localphotoai.photomanager.domain.statistics.StorageStatistics
import com.localphotoai.photomanager.domain.statistics.StorageStatisticsRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class StatsToolFakeStorageStatisticsRepository(private val stats: StorageStatistics) : StorageStatisticsRepository {
    override suspend fun fetchStatistics(): StorageStatistics = stats
}

class GetStorageStatisticsToolTest {

    @Test
    fun `returns the repository's statistics`() = runBlocking {
        val stats = StorageStatistics(
            photoCount = 10, totalSizeBytes = 500L, peopleCount = 2,
            faceCount = 3, duplicateGroupCount = 1, similarGroupCount = 0,
        )
        val tool = GetStorageStatisticsTool(GetStorageStatisticsUseCase(StatsToolFakeStorageStatisticsRepository(stats)))

        val result = tool.execute(ToolCall(tool = ToolName.GET_STORAGE_STATISTICS))

        assertTrue(result is ToolOutcome.Statistics)
        assertEquals(stats, (result as ToolOutcome.Statistics).statistics)
    }
}
