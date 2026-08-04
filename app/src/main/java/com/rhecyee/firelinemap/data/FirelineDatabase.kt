package com.rhecyee.firelinemap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        IncidentEntity::class,
        MapDocumentEntity::class,
        TrackEntity::class,
        MarkerEntity::class,
        ResourcePositionHistoryEntity::class,
        BasemapRegionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FirelineDatabase : RoomDatabase() {
    abstract fun dao(): FirelineDao

    companion object {
        /**
         * Adds the preloaded terrain region table.
         *
         * Written as a real migration rather than a destructive fallback:
         * incident data is field-collected and unrecoverable, so a schema
         * change must never be allowed to drop tracks, markers or photos.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `basemap_regions` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `incidentId` TEXT,
                        `source` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `south` REAL NOT NULL,
                        `west` REAL NOT NULL,
                        `north` REAL NOT NULL,
                        `east` REAL NOT NULL,
                        `minZoom` INTEGER NOT NULL,
                        `maxZoom` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `downloadedAt` INTEGER NOT NULL,
                        `complete` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_basemap_regions_incidentId` " +
                        "ON `basemap_regions` (`incidentId`)"
                )
            }
        }

        fun create(context: Context): FirelineDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FirelineDatabase::class.java,
                "fireline-map.db"
            ).addMigrations(MIGRATION_1_2).build()
    }
}
