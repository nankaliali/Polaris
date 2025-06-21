package com.example.polaris.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cellular_network_data")
data class CellularNetworkDataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "device_id")
    val deviceId: String,                   // ← new field

    // Shared location/timestamp fields
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,

    // Cellular-related fields
    val technology: String,
    @ColumnInfo(name = "plmn_id")
    val plmnId: String,
    val lac: Int,
    val rac: Int,
    val tac: Int,
    @ColumnInfo(name = "cell_id")
    val cellId: Long,
    @ColumnInfo(name = "frequency_band")
    val frequencyBand: String,
    val arfcn: Int,
    @ColumnInfo(name = "actual_frequency")
    val actualFrequency: String,
    val rsrp: Int,
    val rsrq: Int,
    val rscp: Int,
    @ColumnInfo(name = "ec_no")
    val ecNo: Int,
    @ColumnInfo(name = "rx_lev")
    val rxLev: Int,
    val sinr: Int,
    @ColumnInfo(name = "operator_name")
    val operatorName: String,
    val notes: String,

    // Network-test–related fields
    @ColumnInfo(name = "http_upload_rate")
    val httpUploadRate: Double,             // Mbps
    @ColumnInfo(name = "ping_response_time")
    val pingResponseTime: Double,           // ms
    @ColumnInfo(name = "dns_response_time")
    val dnsResponseTime: Double,            // ms
    @ColumnInfo(name = "web_response_time")
    val webResponseTime: Double,            // ms
    @ColumnInfo(name = "sms_delivery_time")
    val smsDeliveryTime: Double,            // ms
    @ColumnInfo(name = "test_notes")
    val testNotes: String
)
