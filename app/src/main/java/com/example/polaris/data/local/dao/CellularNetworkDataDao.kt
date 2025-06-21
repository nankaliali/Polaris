package com.example.polaris.data.local.dao

import androidx.room.*
import com.example.polaris.data.local.entity.CellularNetworkDataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CellularNetworkDataDao {

    /** Insert a single merged record. Returns the newly‐generated row ID (Long). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CellularNetworkDataEntity): Long

    /** Insert a batch of merged records. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CellularNetworkDataEntity>)

    /** Update an existing merged record. */
    @Update
    suspend fun update(entry: CellularNetworkDataEntity)

    /** Delete a particular merged record. */
    @Delete
    suspend fun delete(entry: CellularNetworkDataEntity)

    /**
     * Fetch all rows from `cellular_network_data`, ordered by timestamp descending.
     * (Merged view of both cellular + network‐test data.)
     */
    @Query("SELECT * FROM cellular_network_data ORDER BY timestamp DESC")
    suspend fun getAllEntries(): List<CellularNetworkDataEntity>

    /**
     * Same as above, but in a Flow so the UI can observe changes.
     */
    @Query("SELECT * FROM cellular_network_data ORDER BY timestamp DESC")
    fun getAllEntriesFlow(): Flow<List<CellularNetworkDataEntity>>

    /** Fetch one merged record by its primary key `id`. */
    @Query("SELECT * FROM cellular_network_data WHERE id = :id")
    suspend fun getEntryById(id: Long): CellularNetworkDataEntity?

    /**
     * Fetch all rows whose `timestamp` is between startTime and endTime (inclusive),
     * ordered by timestamp descending.
     */
    @Query("""
        SELECT * 
        FROM cellular_network_data 
        WHERE timestamp BETWEEN :startTime AND :endTime 
        ORDER BY timestamp DESC
    """)
    suspend fun getEntriesByTimeRange(startTime: Long, endTime: Long): List<CellularNetworkDataEntity>

    /**
     * Fetch all rows with a given cellular technology (e.g. "LTE", "GSM", etc.),
     * ordered by timestamp descending.
     */
    @Query("""
        SELECT * 
        FROM cellular_network_data 
        WHERE technology = :technology 
        ORDER BY timestamp DESC
    """)
    suspend fun getEntriesByTechnology(technology: String): List<CellularNetworkDataEntity>

    /**
     * Fetch all rows where `operator_name` (the network operator) matches,
     * ordered by timestamp descending.
     */
    @Query("""
        SELECT * 
        FROM cellular_network_data 
        WHERE operator_name = :operatorName 
        ORDER BY timestamp DESC
    """)
    suspend fun getEntriesByOperator(operatorName: String): List<CellularNetworkDataEntity>

    /** Return a list of distinct technology values present in the table. */
    @Query("SELECT DISTINCT technology FROM cellular_network_data")
    suspend fun getAllTechnologies(): List<String>

    /** Return a list of distinct operator names present in the table. */
    @Query("SELECT DISTINCT operator_name FROM cellular_network_data")
    suspend fun getAllOperators(): List<String>

    /** Delete every row in the table. */
    @Query("DELETE FROM cellular_network_data")
    suspend fun deleteAll()

    /** Delete rows older than a given timestamp. */
    @Query("DELETE FROM cellular_network_data WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldRecords(beforeTimestamp: Long)

    /** Return the total number of rows in the merged table. */
    @Query("SELECT COUNT(*) FROM cellular_network_data")
    suspend fun getRecordCount(): Int


}

