package com.localphotoai.photomanager.domain.statistics

/** A snapshot of library-wide counts, for the `get_storage_statistics` tool (and, later, Phase
 * 11's diagnostics screen — this model is intentionally generic, not tool-specific). */
data class StorageStatistics(
    val photoCount: Int,
    val totalSizeBytes: Long,
    val peopleCount: Int,
    val faceCount: Int,
    val duplicateGroupCount: Int,
    val similarGroupCount: Int,
)

/** Access to aggregate library counts. Implemented in `:data:database` (Room `COUNT`/`SUM` queries only). */
interface StorageStatisticsRepository {
    suspend fun fetchStatistics(): StorageStatistics
}

class GetStorageStatisticsUseCase(
    private val repository: StorageStatisticsRepository,
) {
    suspend operator fun invoke(): StorageStatistics = repository.fetchStatistics()
}
