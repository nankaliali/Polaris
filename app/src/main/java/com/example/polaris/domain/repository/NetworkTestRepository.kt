package com.example.polaris.domain.repository

import com.example.polaris.data.local.entity.CellularNetworkDataEntity

interface NetworkTestRepository {
    suspend fun storeTestResult(result: CellularNetworkDataEntity)
    suspend fun getAllResults(): List<CellularNetworkDataEntity>
}
