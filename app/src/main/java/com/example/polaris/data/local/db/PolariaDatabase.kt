package com.example.polaris.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.polaris.data.local.dao.CellularNetworkDataDao
import com.example.polaris.data.local.dao.LocationDao
import com.example.polaris.data.local.entity.CellularNetworkDataEntity
import com.example.polaris.data.local.entity.LocationEntity

/**
 * After merging CellularDataEntity + NetworkTestEntity into a single table,
 * we no longer reference those old classes here.
 */
@Database(
    entities = [
        LocationEntity::class,
        CellularNetworkDataEntity::class
    ],
    version = 3,            // bumped from 2 → 3
    exportSchema = false
)
abstract class PolariaDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    abstract fun cellularNetworkDataDao(): CellularNetworkDataDao

    companion object {
        @Volatile
        private var INSTANCE: PolariaDatabase? = null

        /**
         * In this example, we use fallbackToDestructiveMigration() so that on upgrade
         * from version 2 → 3, Room will drop any old tables (cellular_data, network_tests, etc.)
         * and re-create the new schema with:
         *   - location
         *   - cellular_network_data
         *
         * If you need to preserve old data, replace this with a proper Migration(2,3).
         */
        fun getInstance(context: Context): PolariaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PolariaDatabase::class.java,
                    "polaria_db"
                )
                    // For development: drop & recreate if schema changes.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
