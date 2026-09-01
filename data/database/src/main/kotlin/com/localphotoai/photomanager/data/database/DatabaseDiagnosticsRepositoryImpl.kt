package com.localphotoai.photomanager.data.database

import android.content.Context
import com.localphotoai.photomanager.domain.diagnostics.DatabaseDiagnosticsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val DATABASE_NAME = "photo-manager.db"

/** Sums the main database file plus its WAL/SHM journal files (Room runs in WAL mode by
 * default) — omitting them would understate the real on-disk footprint whenever a write is
 * in-flight or hasn't been checkpointed yet. */
class DatabaseDiagnosticsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : DatabaseDiagnosticsRepository {
    override suspend fun fetchDatabaseSizeBytes(): Long {
        val base = context.getDatabasePath(DATABASE_NAME)
        val walAndShm = listOf(
            context.getDatabasePath("$DATABASE_NAME-wal"),
            context.getDatabasePath("$DATABASE_NAME-shm"),
        )
        return (listOf(base) + walAndShm).sumOf { if (it.exists()) it.length() else 0L }
    }
}
