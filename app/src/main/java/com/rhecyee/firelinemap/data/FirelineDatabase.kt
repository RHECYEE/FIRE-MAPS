package com.rhecyee.firelinemap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        IncidentEntity::class,
        MapDocumentEntity::class,
        TrackEntity::class,
        MarkerEntity::class,
        ResourcePositionHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FirelineDatabase : RoomDatabase() {
    abstract fun dao(): FirelineDao

    companion object {
        fun create(context: Context): FirelineDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FirelineDatabase::class.java,
                "fireline-map.db"
            ).build()
    }
}
