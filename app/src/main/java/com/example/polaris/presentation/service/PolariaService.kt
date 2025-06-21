package com.example.polaris.presentation.service
import android.provider.Settings

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.telephony.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.polaris.R
import com.example.polaris.data.local.db.PolariaDatabase
import com.example.polaris.data.local.entity.LocationEntity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import android.content.pm.PackageManager
import android.util.Log
import com.example.polaris.data.local.entity.CellularNetworkDataEntity


data class CellularInfo(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val technology: String,
    val plmnId: String,
    val lac: Int,
    val rac: Int,
    val tac: Int,
    val cellId: Long,
    val frequencyBand: String,
    val arfcn: Int,
    val actualFrequency: String,
    val rsrp: Int,
    val rsrq: Int,
    val rscp: Int,
    val ecNo: Int,
    val rxLev: Int,
    val sinr: Int,
    val operatorName: String,
    val notes: String,

)

class PolarisService : Service() {
    private lateinit var networkTestManager: NetworkTestManager
    private val selectedTests = mutableSetOf<String>()

    private var smsTestNumber: String    = ""
    private var pingHost: String         = ""
    private var dnsHost: String          = ""
    private var httpUploadUrl: String    = ""
    private var webTestUrl: String       = ""

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "PolarisService"




    // Drive test state
    private var isDriveTestActive = false
    private var dataCollectionJob: Job? = null
    private var sampleCount = 0

    // Configuration
    private val DATA_COLLECTION_INTERVAL = 2000L // 2 seconds
    private val NOTIFICATION_ID = 1

    companion object {
        const val ACTION_START_DRIVE_TEST = "START_DRIVE_TEST"
        const val ACTION_STOP_DRIVE_TEST = "STOP_DRIVE_TEST"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkTestManager = NetworkTestManager(this)


        Log.i(TAG, "PolarisService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { inIntent ->
            // 1) Extract tests…
            val list = inIntent.getStringArrayListExtra("SELECTED_TESTS")
            selectedTests.clear()
            list?.let { selectedTests.addAll(it) }

            // 2) Pull targets…
            smsTestNumber = inIntent.getStringExtra("SMS_TEST_NUMBER") ?: ""
            pingHost      = inIntent.getStringExtra("PING_HOST")      ?: ""
            dnsHost       = inIntent.getStringExtra("DNS_HOST")       ?: ""
            httpUploadUrl = inIntent.getStringExtra("HTTP_UPLOAD_URL")?: ""
            webTestUrl    = inIntent.getStringExtra("WEB_TEST_URL")   ?: ""

            // 3) Read interval (ms) with default
            val intervalMs = inIntent.getLongExtra("LOOP_INTERVAL_MS", 5_000L)

            // 4) Configure your manager
            networkTestManager.apply {
                smsTestNumber = this@PolarisService.smsTestNumber
                pingHost      = this@PolarisService.pingHost
                dnsHost       = this@PolarisService.dnsHost
                httpUploadUrl = this@PolarisService.httpUploadUrl
                webTestUrl    = this@PolarisService.webTestUrl
            }

            // 5) Decide action now that intervalMs is in scope
            when (inIntent.action) {
                "START_DRIVE_TEST" -> startDriveTest(intervalMs)
                "STOP_DRIVE_TEST"  -> stopDriveTest()
            }
        }

        return START_STICKY
    }

    private fun startDriveTest(intervalMs: Long) {
        if (isDriveTestActive) {
            Log.w(TAG, "Drive test already active")
            return
        }

        isDriveTestActive = true
        sampleCount = 0

        // 1) Start foreground notification
        startForeground(
            NOTIFICATION_ID,
            buildNotification("🚗 Drive test active - Collecting data...")
        )

        // 2) Send status update back to MainActivity
        sendStatusUpdate("📊 Drive Test Status: Active 🔴", true)

        // 3) Periodically collect & save data
        dataCollectionJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isDriveTestActive) {
                    collectAndSaveData()
                    delay(intervalMs)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Data collection cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in data collection loop", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PolarisService,
                        "Error in data collection: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        Log.i(TAG, "Drive test started with targets:")
        Log.i(TAG, "  SMS: $smsTestNumber")
        Log.i(TAG, "  Ping: $pingHost")
        Log.i(TAG, "  DNS: $dnsHost")
        Log.i(TAG, "  HTTP Upload: $httpUploadUrl")
        Log.i(TAG, "  Web: $webTestUrl")
    }

    private fun stopDriveTest() {
        if (!isDriveTestActive) {
            Log.w(TAG, "Drive test not active")
            return
        }

        isDriveTestActive = false
        dataCollectionJob?.cancel()

        updateNotification("✅ Drive test completed - $sampleCount samples collected")

        // Send status update to MainActivity
        sendStatusUpdate("📊 Drive Test Status: Completed ✅", false)

        Log.i(TAG, "Drive test stopped. Total samples: $sampleCount")

        // Stop foreground after a delay to show completion message
        serviceScope.launch {
            delay(3000)
            if (!isDriveTestActive) {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private suspend fun collectAndSaveData() {
        try {
            // Check permissions
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permission not granted")
                return
            }

            // Check GPS
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.w(TAG, "GPS not enabled")
                return
            }

            // Get current location
            val locationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
            val location = locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()

            if (location != null) {
                // Collect cellular information
                val cellularInfo = collectCompleteCellularInfo(location.latitude, location.longitude)

                // Run only selected network tests
                val networkTestResults = networkTestManager.runAllTests(
                    location.latitude,
                    location.longitude,
                    selectedTests
                )

                // Save to database (same as before)
                val db = PolariaDatabase.getInstance(applicationContext)

                val locationEntity = LocationEntity(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = System.currentTimeMillis()
                )
                db.locationDao().insert(locationEntity)

                val ts   = System.currentTimeMillis()
                val lat  = location.latitude
                val lon  = location.longitude

                val tech    = cellularInfo.technology
                val plmn    = cellularInfo.plmnId
                val lac     = cellularInfo.lac
                val rac     = cellularInfo.rac
                val tac     = cellularInfo.tac
                val cellId  = cellularInfo.cellId
                val band    = cellularInfo.frequencyBand
                val arfcn   = cellularInfo.arfcn
                val freq    = cellularInfo.actualFrequency
                val rsrp    = cellularInfo.rsrp
                val rsrq    = cellularInfo.rsrq
                val rscp    = cellularInfo.rscp
                val ecNo    = cellularInfo.ecNo
                val rxLev   = cellularInfo.rxLev
                val sinr    = cellularInfo.sinr
                val opName  = cellularInfo.operatorName
                val notes   = cellularInfo.notes

                val uploadRate = networkTestResults.httpUploadRate   ?: -1.0
                val pingRt     = networkTestResults.pingResponseTime ?: -1.0
                val dnsRt      = networkTestResults.dnsResponseTime  ?: -1.0
                val webRt      = networkTestResults.webResponseTime  ?: -1.0
                val smsRt      = networkTestResults.smsDeliveryTime  ?: -1.0
                val testLog    = networkTestResults.testNotes

                val deviceIdString = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: "unknown_device"

                val mergedEntity = CellularNetworkDataEntity(
                    deviceId           = deviceIdString,
                    timestamp          = ts,
                    latitude           = lat,
                    longitude          = lon,
                    technology         = tech,
                    plmnId             = plmn,
                    lac                = lac,
                    rac                = rac,
                    tac                = tac,
                    cellId             = cellId,
                    frequencyBand      = band,
                    arfcn              = arfcn,
                    actualFrequency    = freq,
                    rsrp               = rsrp,
                    rsrq               = rsrq,
                    rscp               = rscp,
                    ecNo               = ecNo,
                    rxLev              = rxLev,
                    sinr               = sinr,
                    operatorName       = opName,
                    notes              = notes,
                    httpUploadRate     = uploadRate,
                    pingResponseTime   = pingRt,
                    dnsResponseTime    = dnsRt,
                    webResponseTime    = webRt,
                    smsDeliveryTime    = smsRt,
                    testNotes          = testLog
                )
                db.cellularNetworkDataDao().insert(mergedEntity)


                sampleCount++

                // Update notification with selected tests info
                val testsInfo = if (selectedTests.isNotEmpty()) {
                    " (${selectedTests.size} tests)"
                } else {
                    ""
                }
                updateNotification("🚗 Collecting data$testsInfo... Sample #$sampleCount")

                // Send data to MainActivity for display
                sendDataUpdate(cellularInfo, networkTestResults)

                Log.d(TAG, "Data sample #$sampleCount collected with selected tests: $selectedTests")

            } else {
                Log.w(TAG, "Location is null")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error collecting data sample", e)
        }
    }

    private fun sendDataUpdate(cellularInfo: CellularInfo,networkTestResults: NetworkTestResults) {
        val dataString = buildString {
            appendLine("📶 ${cellularInfo.technology} - ${cellularInfo.operatorName}")
            appendLine("📍 ${String.format("%.6f", cellularInfo.latitude)}, ${String.format("%.6f", cellularInfo.longitude)}")

            if (cellularInfo.cellId != -1L) {
                appendLine("📡 Cell ID: ${cellularInfo.cellId}")
            }

            if (cellularInfo.arfcn != -1) {
                appendLine("📻 ARFCN: ${cellularInfo.arfcn}")
                appendLine("🎵 ${cellularInfo.actualFrequency}")
            }

            // Show signal quality based on technology
            when (cellularInfo.technology) {
                "LTE", "5G" -> {
                    if (cellularInfo.rsrp != Int.MAX_VALUE) {
                        appendLine("📶 RSRP: ${cellularInfo.rsrp} dBm")
                    }
                    if (cellularInfo.rsrq != Int.MAX_VALUE) {
                        appendLine("📶 RSRQ: ${cellularInfo.rsrq} dB")
                    }
                }
                "UMTS", "HSPA", "HSPA+" -> {
                    if (cellularInfo.rscp != Int.MAX_VALUE) {
                        appendLine("📶 RSCP: ${cellularInfo.rscp} dBm")
                    }
                }
                "GSM", "GPRS", "EDGE" -> {
                    if (cellularInfo.rxLev != Int.MAX_VALUE) {
                        appendLine("📶 RxLev: ${cellularInfo.rxLev} dBm")
                    }
                }
            }
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("🌐 Network Performance:")

            with(networkTestResults) {
                if (httpUploadRate != null && httpUploadRate > 0) {
                    appendLine("📤 Upload: ${String.format("%.2f", httpUploadRate)} Mbps")
                }
                if (pingResponseTime != null && pingResponseTime > 0) {
                    appendLine("⚡ Ping: ${pingResponseTime}ms")
                }
                if (dnsResponseTime != null && dnsResponseTime > 0) {
                    appendLine("🔍 DNS: ${dnsResponseTime}ms")
                }
                if (webResponseTime != null && webResponseTime > 0) {
                    appendLine("🌐 Web: ${webResponseTime}ms")
                }
                if (smsDeliveryTime != null && smsDeliveryTime > 0) {
                    appendLine("💬 SMS: ${smsDeliveryTime}ms")
                }
            }

            appendLine("Sample #$sampleCount")
        }

        val intent = Intent("com.example.polaris.DRIVE_TEST_DATA").apply {
            putExtra("data", dataString)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendStatusUpdate(status: String, isActive: Boolean) {
        val intent = Intent("com.example.polaris.DRIVE_TEST_STATUS").apply {
            putExtra("status", status)
            putExtra("isActive", isActive)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateNotification(content: String) {
        val notification = buildNotification(content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun collectCompleteCellularInfo(latitude: Double, longitude: Double): CellularInfo {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        var cellularInfo = CellularInfo(
            latitude = latitude,
            longitude = longitude,
            timestamp = System.currentTimeMillis(),
            technology = "Unknown",
            plmnId = "",
            lac = -1,
            rac = -1,
            tac = -1,
            cellId = -1,
            frequencyBand = "Unknown",
            arfcn = -1,
            actualFrequency = "Unknown",
            rsrp = Int.MAX_VALUE,
            rsrq = Int.MAX_VALUE,
            rscp = Int.MAX_VALUE,
            ecNo = Int.MAX_VALUE,
            rxLev = Int.MAX_VALUE,
            sinr = Int.MAX_VALUE,
            operatorName = telephonyManager.networkOperatorName ?: "Unknown",
            notes = ""
        )

        val notes = mutableListOf<String>()

        try {
            // PLMN ID
            val networkOperator = telephonyManager.networkOperator
            if (networkOperator.isNotEmpty() && networkOperator.length >= 5) {
                cellularInfo = cellularInfo.copy(plmnId = networkOperator)
            } else {
                notes.add("PLMN ID not available")
            }

            // Network Type
            val networkType = telephonyManager.networkType
            val tech = when (networkType) {
                TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                else -> "Unknown ($networkType)"
            }
            cellularInfo = cellularInfo.copy(technology = tech)

            // Cell Info Analysis
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val cellInfoList = telephonyManager.allCellInfo

                for (cellInfo in cellInfoList) {
                    if (cellInfo.isRegistered) {
                        cellularInfo = when (cellInfo) {
                            is CellInfoLte -> analyzeLteCell(cellInfo, cellularInfo, notes)
                            is CellInfoWcdma -> analyzeWcdmaCell(cellInfo, cellularInfo, notes)
                            is CellInfoGsm -> analyzeGsmCell(cellInfo, cellularInfo, notes)
                            is CellInfoNr -> analyze5GCell(cellInfo, cellularInfo, notes)
                            else -> {
                                notes.add("Unsupported cell type: ${cellInfo.javaClass.simpleName}")
                                cellularInfo
                            }
                        }
                        break // Only process the registered cell
                    }
                }
            } else {
                notes.add("Fine location permission required for detailed cell info")
            }

        } catch (e: SecurityException) {
            notes.add("Security exception: ${e.message}")
        } catch (e: Exception) {
            notes.add("Error collecting cellular info: ${e.message}")
        }

        return cellularInfo.copy(notes = notes.joinToString("; "))
    }

    private fun analyzeLteCell(cellInfo: CellInfoLte, info: CellularInfo, notes: MutableList<String>): CellularInfo {
        val cellIdentity = cellInfo.cellIdentity
        val signalStrength = cellInfo.cellSignalStrength

        var updatedInfo = info

        try {
            // Cell identifiers
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                updatedInfo = updatedInfo.copy(
                    cellId = cellIdentity.ci.toLong(),
                    tac = cellIdentity.tac
                )
            } else {
                notes.add("TAC and CI require API 28+")
            }

            // ARFCN and frequency
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val earfcn = cellIdentity.earfcn
                updatedInfo = updatedInfo.copy(
                    arfcn = earfcn,
                    actualFrequency = calculateLteFrequency(earfcn),
                    frequencyBand = getLteBand(earfcn)
                )
            } else {
                notes.add("EARFCN requires API 24+")
            }

            // Signal quality
            updatedInfo = updatedInfo.copy(
                rsrp = signalStrength.rsrp,
                rsrq = signalStrength.rsrq,
                sinr = signalStrength.rssnr
            )

        } catch (e: Exception) {
            notes.add("LTE analysis error: ${e.message}")
        }

        return updatedInfo
    }

    private fun analyzeWcdmaCell(cellInfo: CellInfoWcdma, info: CellularInfo, notes: MutableList<String>): CellularInfo {
        val cellIdentity = cellInfo.cellIdentity
        val signalStrength = cellInfo.cellSignalStrength

        var updatedInfo = info

        try {
            // Cell identifiers
            updatedInfo = updatedInfo.copy(
                cellId = cellIdentity.cid.toLong(),
                lac = cellIdentity.lac
            )

            // ARFCN and frequency
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val uarfcn = cellIdentity.uarfcn
                updatedInfo = updatedInfo.copy(
                    arfcn = uarfcn,
                    actualFrequency = calculateUmtsFrequency(uarfcn),
                    frequencyBand = getUmtsBand(uarfcn)
                )
            } else {
                notes.add("UARFCN requires API 24+")
            }

            // Signal quality
            try {
                val dbm = signalStrength.dbm
                updatedInfo = updatedInfo.copy(rscp = dbm)
            } catch (e: Exception) {
                notes.add("RSCP not available: ${e.message}")
            }

            try {
                val asu = signalStrength.asuLevel
                if (asu != 99) {
                    updatedInfo = updatedInfo.copy(ecNo = asu)
                }
            } catch (e: Exception) {
                notes.add("ASU/EcNo not available: ${e.message}")
            }

        } catch (e: Exception) {
            notes.add("WCDMA analysis error: ${e.message}")
        }

        return updatedInfo
    }

    private fun analyzeGsmCell(cellInfo: CellInfoGsm, info: CellularInfo, notes: MutableList<String>): CellularInfo {
        val cellIdentity = cellInfo.cellIdentity
        val signalStrength = cellInfo.cellSignalStrength

        var updatedInfo = info

        try {
            // Cell identifiers
            updatedInfo = updatedInfo.copy(
                cellId = cellIdentity.cid.toLong(),
                lac = cellIdentity.lac
            )

            // ARFCN and frequency
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val arfcn = cellIdentity.arfcn
                updatedInfo = updatedInfo.copy(
                    arfcn = arfcn,
                    actualFrequency = calculateGsmFrequency(arfcn),
                    frequencyBand = getGsmBand(arfcn)
                )
            } else {
                notes.add("ARFCN requires API 24+")
            }

            // Signal quality
            updatedInfo = updatedInfo.copy(
                rxLev = signalStrength.dbm
            )

        } catch (e: Exception) {
            notes.add("GSM analysis error: ${e.message}")
        }

        return updatedInfo
    }

    private fun analyze5GCell(cellInfo: CellInfoNr, info: CellularInfo, notes: MutableList<String>): CellularInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cellIdentity = cellInfo.cellIdentity as CellIdentityNr
                val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthNr

                var updatedInfo = info

                // Cell identifiers
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        updatedInfo = updatedInfo.copy(
                            cellId = cellIdentity.nci,
                            tac = cellIdentity.tac
                        )
                    } catch (e: Exception) {
                        notes.add("5G cell identifiers not available: ${e.message}")
                    }
                }

                // ARFCN and frequency
                try {
                    val nrarfcn = cellIdentity.nrarfcn
                    updatedInfo = updatedInfo.copy(
                        arfcn = nrarfcn,
                        actualFrequency = calculate5GFrequency(nrarfcn),
                        frequencyBand = get5GBand(nrarfcn)
                    )
                } catch (e: Exception) {
                    notes.add("5G ARFCN not available: ${e.message}")
                }

                // Signal quality
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        updatedInfo = updatedInfo.copy(
                            rsrp = signalStrength.ssRsrp,
                            rsrq = signalStrength.ssRsrq,
                            sinr = signalStrength.ssSinr
                        )
                    } catch (e: Exception) {
                        notes.add("5G signal strength not available: ${e.message}")
                    }
                } else {
                    notes.add("5G signal measurements require API 30+")
                }

                return updatedInfo

            } catch (e: Exception) {
                notes.add("5G analysis error: ${e.message}")
            }
        } else {
            notes.add("5G analysis requires API 29+")
        }
        return info
    }

    // Frequency calculation methods
    private fun calculateLteFrequency(earfcn: Int): String {
        return when {
            earfcn in 0..599 -> "${2110 + 0.1 * earfcn} MHz (Band 1)"
            earfcn in 600..1199 -> "${1930 + 0.1 * (earfcn - 600)} MHz (Band 2)"
            earfcn in 1200..1949 -> "${1805 + 0.1 * (earfcn - 1200)} MHz (Band 3)"
            earfcn in 1950..2399 -> "${2110 + 0.1 * (earfcn - 1950)} MHz (Band 4)"
            earfcn in 2400..2649 -> "${869 + 0.1 * (earfcn - 2400)} MHz (Band 5)"
            earfcn in 2650..2749 -> "${2620 + 0.1 * (earfcn - 2650)} MHz (Band 7)"
            earfcn in 3450..3799 -> "${1805 + 0.1 * (earfcn - 3450)} MHz (Band 8)"
            else -> "$earfcn (Unknown band)"
        }
    }

    private fun calculateUmtsFrequency(uarfcn: Int): String {
        return when {
            uarfcn in 10562..10838 -> "${2110 + 0.2 * (uarfcn - 10562)} MHz (Band 1)"
            uarfcn in 9662..9938 -> "${1930 + 0.2 * (uarfcn - 9662)} MHz (Band 2)"
            uarfcn in 1162..1513 -> "${1805 + 0.2 * (uarfcn - 1162)} MHz (Band 3)"
            else -> "$uarfcn (Unknown band)"
        }
    }

    private fun calculateGsmFrequency(arfcn: Int): String {
        return when {
            arfcn in 0..124 -> "${935 + 0.2 * arfcn} MHz (GSM900)"
            arfcn in 512..885 -> "${1805 + 0.2 * (arfcn - 512)} MHz (DCS1800)"
            arfcn in 128..251 -> "${824.2 + 0.2 * (arfcn - 128)} MHz (GSM850)"
            else -> "$arfcn (Unknown band)"
        }
    }

    private fun calculate5GFrequency(nrarfcn: Int): String {
        return when {
            nrarfcn in 422000..434000 -> "${3300 + 0.015 * (nrarfcn - 422000)} MHz (n78)"
            nrarfcn in 384000..396000 -> "${1805 + 0.015 * (nrarfcn - 384000)} MHz (n3)"
            else -> "$nrarfcn (Unknown 5G band)"
        }
    }

    // Band identification methods
    private fun getLteBand(earfcn: Int): String {
        return when {
            earfcn in 0..599 -> "Band 1 (2100 MHz)"
            earfcn in 600..1199 -> "Band 2 (1900 MHz)"
            earfcn in 1200..1949 -> "Band 3 (1800 MHz)"
            earfcn in 1950..2399 -> "Band 4 (1700/2100 MHz)"
            earfcn in 2400..2649 -> "Band 5 (850 MHz)"
            earfcn in 2650..2749 -> "Band 7 (2600 MHz)"
            earfcn in 3450..3799 -> "Band 8 (900 MHz)"
            else -> "Unknown LTE Band"
        }
    }

    private fun getUmtsBand(uarfcn: Int): String {
        return when {
            uarfcn in 10562..10838 -> "Band I (2100 MHz)"
            uarfcn in 9662..9938 -> "Band II (1900 MHz)"
            uarfcn in 1162..1513 -> "Band III (1800 MHz)"
            else -> "Unknown UMTS Band"
        }
    }

    private fun getGsmBand(arfcn: Int): String {
        return when {
            arfcn in 0..124 -> "GSM900"
            arfcn in 512..885 -> "DCS1800"
            arfcn in 128..251 -> "GSM850"
            else -> "Unknown GSM Band"
        }
    }

    private fun get5GBand(nrarfcn: Int): String {
        return when {
            nrarfcn in 422000..434000 -> "n78 (3500 MHz)"
            nrarfcn in 384000..396000 -> "n3 (1800 MHz)"
            else -> "Unknown 5G Band"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isDriveTestActive = false
        dataCollectionJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "PolarisService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "polaria_channel",
                "Polaris Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, "polaria_channel")
            .setContentTitle("Polaris Drive Test")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(isDriveTestActive)
            .build()
    }
}