package com.localphotoai.photomanager.domain.diagnostics

/** The one piece of diagnostics info that isn't already exposed by an existing repository/use
 * case — the Room database's on-disk size. Implemented in `:data:database`, the only module with
 * access to the database file's path. */
interface DatabaseDiagnosticsRepository {
    suspend fun fetchDatabaseSizeBytes(): Long
}
