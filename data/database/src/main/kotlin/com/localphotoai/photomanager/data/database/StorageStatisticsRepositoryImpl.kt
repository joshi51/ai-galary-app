package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.StatisticsDao
import com.localphotoai.photomanager.domain.statistics.StorageStatistics
import com.localphotoai.photomanager.domain.statistics.StorageStatisticsRepository
import javax.inject.Inject

class StorageStatisticsRepositoryImpl @Inject constructor(
    private val statisticsDao: StatisticsDao,
) : StorageStatisticsRepository {
    override suspend fun fetchStatistics(): StorageStatistics = StorageStatistics(
        photoCount = statisticsDao.photoCount(),
        totalSizeBytes = statisticsDao.totalSizeBytes(),
        peopleCount = statisticsDao.peopleCount(),
        faceCount = statisticsDao.faceCount(),
        duplicateGroupCount = statisticsDao.duplicateGroupCount(),
        similarGroupCount = statisticsDao.similarGroupCount(),
    )
}
