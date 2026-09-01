package com.localphotoai.photomanager.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.localphotoai.photomanager.data.database.dao.ClusteringStatusDao
import com.localphotoai.photomanager.data.database.dao.DuplicateGroupDao
import com.localphotoai.photomanager.data.database.dao.EmbeddingDao
import com.localphotoai.photomanager.data.database.dao.EmbeddingStatusDao
import com.localphotoai.photomanager.data.database.dao.FaceDao
import com.localphotoai.photomanager.data.database.dao.FaceDetectionStatusDao
import com.localphotoai.photomanager.data.database.dao.IndexingStatusDao
import com.localphotoai.photomanager.data.database.dao.PersonDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.dao.SearchDao
import com.localphotoai.photomanager.data.database.dao.SimilarGroupDao
import com.localphotoai.photomanager.data.database.dao.SimilarityEmbeddingDao
import com.localphotoai.photomanager.data.database.dao.AlbumDao
import com.localphotoai.photomanager.data.database.dao.OrganizationDao
import com.localphotoai.photomanager.data.database.dao.StatisticsDao
import com.localphotoai.photomanager.data.database.entity.AlbumEntity
import com.localphotoai.photomanager.data.database.entity.AlbumPhotoEntity
import com.localphotoai.photomanager.data.database.entity.ClusteringStatusEntity
import com.localphotoai.photomanager.data.database.entity.DuplicateGroupEntity
import com.localphotoai.photomanager.data.database.entity.DuplicateGroupMemberEntity
import com.localphotoai.photomanager.data.database.entity.EmbeddingEntity
import com.localphotoai.photomanager.data.database.entity.EmbeddingStatusEntity
import com.localphotoai.photomanager.data.database.entity.FaceDetectionStatusEntity
import com.localphotoai.photomanager.data.database.entity.FaceEntity
import com.localphotoai.photomanager.data.database.entity.IndexingStatusEntity
import com.localphotoai.photomanager.data.database.entity.OrganizationOperationEntity
import com.localphotoai.photomanager.data.database.entity.OrganizationPlanEntity
import com.localphotoai.photomanager.data.database.entity.PersonEntity
import com.localphotoai.photomanager.data.database.entity.PersonFaceEntity
import com.localphotoai.photomanager.data.database.entity.PhotoEntity
import com.localphotoai.photomanager.data.database.entity.SimilarGroupEntity
import com.localphotoai.photomanager.data.database.entity.SimilarGroupMemberEntity
import com.localphotoai.photomanager.data.database.entity.SimilarityEmbeddingEntity

@Database(
    entities = [
        PhotoEntity::class,
        IndexingStatusEntity::class,
        FaceEntity::class,
        FaceDetectionStatusEntity::class,
        EmbeddingEntity::class,
        EmbeddingStatusEntity::class,
        PersonEntity::class,
        PersonFaceEntity::class,
        ClusteringStatusEntity::class,
        DuplicateGroupEntity::class,
        DuplicateGroupMemberEntity::class,
        SimilarGroupEntity::class,
        SimilarGroupMemberEntity::class,
        SimilarityEmbeddingEntity::class,
        AlbumEntity::class,
        AlbumPhotoEntity::class,
        OrganizationPlanEntity::class,
        OrganizationOperationEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun indexingStatusDao(): IndexingStatusDao
    abstract fun faceDao(): FaceDao
    abstract fun faceDetectionStatusDao(): FaceDetectionStatusDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun embeddingStatusDao(): EmbeddingStatusDao
    abstract fun personDao(): PersonDao
    abstract fun clusteringStatusDao(): ClusteringStatusDao
    abstract fun searchDao(): SearchDao
    abstract fun duplicateGroupDao(): DuplicateGroupDao
    abstract fun similarGroupDao(): SimilarGroupDao
    abstract fun statisticsDao(): StatisticsDao
    abstract fun similarityEmbeddingDao(): SimilarityEmbeddingDao
    abstract fun albumDao(): AlbumDao
    abstract fun organizationDao(): OrganizationDao
}

/** Phase 3: adds face detection — new `faces`/`face_detection_status` tables, two new `photos` columns. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN facesDetectedAt INTEGER")
        db.execSQL("ALTER TABLE photos ADD COLUMN faceDetectionError TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS faces (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                photoId INTEGER NOT NULL,
                left REAL NOT NULL,
                top REAL NOT NULL,
                right REAL NOT NULL,
                bottom REAL NOT NULL,
                confidence REAL NOT NULL,
                rotationDegrees INTEGER NOT NULL,
                markedIncorrect INTEGER NOT NULL,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_faces_photoId ON faces(photoId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS face_detection_status (
                id INTEGER PRIMARY KEY NOT NULL,
                state TEXT NOT NULL,
                itemsProcessed INTEGER NOT NULL,
                itemsTotal INTEGER NOT NULL,
                lastRunAtMs INTEGER NOT NULL,
                lastError TEXT
            )
            """.trimIndent(),
        )
    }
}

/** Phase 4: adds face embeddings — `embeddings`/`embedding_status` tables, two new `faces` columns. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE faces ADD COLUMN embeddingVersion INTEGER")
        db.execSQL("ALTER TABLE faces ADD COLUMN embeddingError TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS embeddings (
                faceId INTEGER PRIMARY KEY NOT NULL,
                modelVersion INTEGER NOT NULL,
                vector BLOB NOT NULL,
                FOREIGN KEY(faceId) REFERENCES faces(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS embedding_status (
                id INTEGER PRIMARY KEY NOT NULL,
                state TEXT NOT NULL,
                itemsProcessed INTEGER NOT NULL,
                itemsTotal INTEGER NOT NULL,
                lastRunAtMs INTEGER NOT NULL,
                lastError TEXT
            )
            """.trimIndent(),
        )
    }
}

/** Phase 5: adds people/clustering — `people`/`person_faces`/`clustering_status` tables. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS people (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT,
                representativeFaceId INTEGER,
                createdAt INTEGER NOT NULL,
                clusterAlgoVersion INTEGER NOT NULL,
                centroidSum BLOB NOT NULL,
                memberCount INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_faces (
                faceId INTEGER PRIMARY KEY NOT NULL,
                personId INTEGER NOT NULL,
                clusterConfidence REAL NOT NULL,
                FOREIGN KEY(personId) REFERENCES people(id) ON DELETE CASCADE,
                FOREIGN KEY(faceId) REFERENCES faces(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_person_faces_personId ON person_faces(personId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS clustering_status (
                id INTEGER PRIMARY KEY NOT NULL,
                state TEXT NOT NULL,
                itemsProcessed INTEGER NOT NULL,
                itemsTotal INTEGER NOT NULL,
                lastRunAtMs INTEGER NOT NULL,
                lastError TEXT
            )
            """.trimIndent(),
        )
    }
}

/** Phase 6: adds search indexes on `photos` for date and location filtering. No data changes. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_dateTakenMs ON photos(dateTakenMs)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_photos_latitude_longitude ON photos(latitude, longitude)",
        )
    }
}

/** Phase 7: adds duplicate/near-duplicate/burst/similar detection — new `photos` hash and
 *  similarity-embedding-status columns, plus four new tables. No data changes to existing rows. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN contentHash TEXT")
        db.execSQL("ALTER TABLE photos ADD COLUMN perceptualHash INTEGER")
        db.execSQL("ALTER TABLE photos ADD COLUMN hashError TEXT")
        db.execSQL("ALTER TABLE photos ADD COLUMN similarityEmbeddingVersion INTEGER")
        db.execSQL("ALTER TABLE photos ADD COLUMN similarityEmbeddingError TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_contentHash ON photos(contentHash)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS duplicate_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                contentHash TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS duplicate_group_members (
                photoId INTEGER PRIMARY KEY NOT NULL,
                groupId INTEGER NOT NULL,
                FOREIGN KEY(groupId) REFERENCES duplicate_groups(id) ON DELETE CASCADE,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_group_members_groupId ON duplicate_group_members(groupId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS similar_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind TEXT NOT NULL,
                avgSimilarity REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS similar_group_members (
                photoId INTEGER NOT NULL,
                groupId INTEGER NOT NULL,
                similarityToRepresentative REAL NOT NULL,
                PRIMARY KEY(photoId, groupId),
                FOREIGN KEY(groupId) REFERENCES similar_groups(id) ON DELETE CASCADE,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_similar_group_members_groupId ON similar_group_members(groupId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_similar_group_members_photoId ON similar_group_members(photoId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS similarity_embeddings (
                photoId INTEGER PRIMARY KEY NOT NULL,
                modelVersion INTEGER NOT NULL,
                vector BLOB NOT NULL,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

/** Phase 9: relativePath column, album tables, organization-plan tables. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN relativePath TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS albums (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAtMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS album_photos (
                albumId INTEGER NOT NULL,
                photoId INTEGER NOT NULL,
                PRIMARY KEY(albumId, photoId),
                FOREIGN KEY(albumId) REFERENCES albums(id) ON DELETE CASCADE,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_album_photos_albumId ON album_photos(albumId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_album_photos_photoId ON album_photos(photoId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS organization_plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                requestText TEXT NOT NULL,
                category TEXT NOT NULL,
                createdAtMs INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS organization_operations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                planId INTEGER NOT NULL,
                opType TEXT NOT NULL,
                source TEXT,
                destination TEXT NOT NULL,
                reason TEXT NOT NULL,
                confidence REAL,
                memberPhotoIdsCsv TEXT,
                reviewStatus TEXT NOT NULL,
                executionResult TEXT,
                executionError TEXT,
                FOREIGN KEY(planId) REFERENCES organization_plans(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_organization_operations_planId ON organization_operations(planId)")
    }
}
