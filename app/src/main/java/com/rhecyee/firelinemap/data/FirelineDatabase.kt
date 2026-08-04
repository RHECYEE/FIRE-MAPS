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
        BasemapRegionEntity::class,
        MedicalReportEntity::class,
        MedicalReportUpdateEntity::class
    ],
    version = 3,
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

        /** Adds medical incident reports and their running updates. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medical_reports` (
                        `id` TEXT NOT NULL, `incidentId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                        `incidentName` TEXT NOT NULL, `mapName` TEXT,
                        `latitude` REAL NOT NULL, `longitude` REAL NOT NULL,
                        `elevationMeters` REAL, `accuracyMeters` REAL,
                        `reporterName` TEXT, `reporterQualification` TEXT,
                        `priority` TEXT NOT NULL, `patientCount` INTEGER NOT NULL,
                        `transport` TEXT NOT NULL, `resources` TEXT NOT NULL,
                        `natureOfInjury` TEXT, `patientAssessment` TEXT,
                        `lzHazards` TEXT, `notes` TEXT,
                        `incidentCommander` TEXT, `medicalProvider` TEXT,
                        `groundContact` TEXT, `markerId` TEXT, `trackId` TEXT,
                        `photoCount` INTEGER NOT NULL, `format` TEXT NOT NULL,
                        `closedAt` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`incidentId`) REFERENCES `incidents`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medical_reports_incidentId` " +
                        "ON `medical_reports` (`incidentId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medical_report_updates` (
                        `id` TEXT NOT NULL, `reportId` TEXT NOT NULL,
                        `recordedAt` INTEGER NOT NULL, `text` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`reportId`) REFERENCES `medical_reports`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medical_report_updates_reportId` " +
                        "ON `medical_report_updates` (`reportId`)"
                )
            }
        }

        fun create(context: Context): FirelineDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FirelineDatabase::class.java,
                "fireline-map.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
