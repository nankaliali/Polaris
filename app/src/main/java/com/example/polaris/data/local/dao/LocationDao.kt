package com.example.polaris.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.polaris.data.local.entity.LocationEntity

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(location: LocationEntity)

    @Query("SELECT * FROM locations ORDER BY timestamp DESC")
    suspend fun getAll(): List<LocationEntity>

    @Query("SELECT COUNT(*) FROM locations")
    suspend fun getCount(): Int

    @Query("DELETE FROM locations WHERE timestamp < :timestamp")
    suspend fun deleteOldRecords(timestamp: Long): Int
}