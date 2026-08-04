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
        MedicalReportUpdateEntity::class,
        OfflineRegionEntity::class
    ],
    version = 6,
    exportSchema = true
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
                        `hasPosition` INTEGER NOT NULL DEFAULT 1,
                        `radioNameOverride` TEXT, `closedAt` INTEGER,
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

        /** Adds the shared optional-layer library. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `layer_packages` (
                        `id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `countyFips` TEXT, `stateCode` TEXT,
                        `filePath` TEXT NOT NULL, `format` TEXT NOT NULL,
                        `source` TEXT, `sourceUpdatedAt` INTEGER,
                        `importedAt` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL, `opacity` REAL NOT NULL,
                        `showOwner` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `offline_regions` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `polygonJson` TEXT NOT NULL,
                        `south` REAL NOT NULL, `west` REAL NOT NULL,
                        `north` REAL NOT NULL, `east` REAL NOT NULL,
                        `minZoom` INTEGER NOT NULL, `maxZoom` INTEGER NOT NULL,
                        `layers` TEXT NOT NULL, `contourIntervalFeet` INTEGER NOT NULL,
                        `status` TEXT NOT NULL, `estimatedBytes` INTEGER NOT NULL,
                        `downloadedBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL, `completedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                // Carried here too, so a database that never reached 4 through
                // a working build is repaired on the way past.
                addColumnIfMissing(db, "medical_reports", "radioNameOverride", "TEXT")
                addColumnIfMissing(
                    db, "medical_reports", "hasPosition", "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * Repairs medical_reports.
         *
         * Two fields were added to the entity without the database version
         * being raised with them, so an install that already held the older
         * table opened against a schema Room did not recognise and threw. The
         * columns are added if they are absent; an install that already has
         * them fails the statement harmlessly and carries on.
         *
         * The lesson is the obvious one: a shipped migration is history and
         * must not be edited, and a field added to an entity always costs a
         * version.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "medical_reports", "radioNameOverride", "TEXT")
                addColumnIfMissing(
                    db, "medical_reports", "hasPosition", "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * SQLite has no ADD COLUMN IF NOT EXISTS, and a migration must not
         * take the app down for having already been applied.
         */
        /**
         * Drops the parcel layer table.
         *
         * The layer is gone: no free source of parcel geometry exists, and a
         * switch that can never be turned on is worse than no switch. The
         * table goes with it rather than being left behind, because a schema
         * carrying tables nothing writes is a schema nobody can read.
         *
         * Nothing field-collected lives here -- a layer package was a pointer
         * to an imported file, not a record of anything anyone observed -- so
         * this is the one table that can be dropped without losing work.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS layer_packages")
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            val present = runCatching {
                db.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                        .toSet()
                }
            }.getOrDefault(emptySet())
            if (column in present) return
            runCatching { db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition") }
        }

        fun create(context: Context): FirelineDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FirelineDatabase::class.java,
                "fireline-map.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
            )
                .build()
    }
}
