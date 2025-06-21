package com.example.polaris.presentation.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody


data class NetworkTestResults(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val httpUploadRate: Double? = -1.0,
    val pingResponseTime: Double? = -1.0,
    val dnsResponseTime: Double? = -1.0,
    val webResponseTime: Double? = -1.0,
    val smsDeliveryTime: Double? = -1.0,
    val testNotes: String = ""
)

class NetworkTestManager(private val context: Context) {

    private val TAG = "NetworkTestManager"
    var smsTestNumber: String    = ""
    var pingHost: String         = ""
    var dnsHost: String          = ""
    var httpUploadUrl: String    = ""
    var webTestUrl: String       = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()



    suspend fun runAllTests(latitude: Double, longitude: Double, selectedTests: Set<String> = setOf("HTTP Upload", "Ping Test", "DNS Test", "Web Test", "SMS Test")): NetworkTestResults {
        val timestamp = System.currentTimeMillis()

        return withContext(Dispatchers.IO) {
            val results = NetworkTestResults(
                timestamp = timestamp,
                latitude = latitude,
                longitude = longitude
            )

            val testResults = mutableListOf<String>()
            var httpUploadRate: Double? = null
            var pingResponseTime: Double? = null
            var dnsResponseTime: Double? = null
            var webResponseTime: Double? = null
            var smsDeliveryTime: Double? = null

            try {
                // Run only selected tests
                val deferredResults = mutableMapOf<String, Deferred<Pair<Double?, String>>>()

                if (selectedTests.contains("HTTP Upload")) {
                    deferredResults["HTTP Upload"] = async { runHttpUploadTest() }
                }
                if (selectedTests.contains("Ping Test")) {
                    deferredResults["Ping Test"] = async { runPingTest() }
                }
                if (selectedTests.contains("DNS Test")) {
                    deferredResults["DNS Test"] = async { runDnsTest() }
                }
                if (selectedTests.contains("Web Test")) {
                    deferredResults["Web Test"] = async { runWebTest() }
                }
                if (selectedTests.contains("SMS Test")) {
                    deferredResults["SMS Test"] = async { runSmsTest() }
                }

                // Collect results
                deferredResults.forEach { (testName, deferred) ->
                    val (result, note) = deferred.await()
                    when (testName) {
                        "HTTP Upload" -> httpUploadRate = result
                        "Ping Test" -> pingResponseTime = result
                        "DNS Test" -> dnsResponseTime = result
                        "Web Test" -> webResponseTime = result
                        "SMS Test" -> smsDeliveryTime = result
                    }
                    if (note.isNotEmpty()) testResults.add("$testName: $note")
                }

                results.copy(
                    httpUploadRate = httpUploadRate,
                    pingResponseTime = pingResponseTime,
                    dnsResponseTime = dnsResponseTime,
                    webResponseTime = webResponseTime,
                    smsDeliveryTime = smsDeliveryTime,
                    testNotes = testResults.joinToString("; ")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error running selected network tests", e)
                results.copy(testNotes = "Test error: ${e.message}")
            }
        }
    }
//
    private suspend fun runHttpUploadTest(): Pair<Double?, String> {
        return try {
            Log.d(TAG, "Starting HTTP upload test to $httpUploadUrl ...")
            var url = httpUploadUrl
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$httpUploadUrl"
            }
            httpUploadUrl = url
            // Create 1KB of test data
            val testData = "x".repeat(1024).toByteArray()
            val requestBody = testData.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(httpUploadUrl)  // <-- now uses the property
                .post(requestBody)
                .build()

            val startTime = System.nanoTime()
            val response = client.newCall(request).execute()
            val endTime = System.nanoTime()

            response.use {
                val duration = (endTime - startTime) / 1_000_000.0
                if (it.isSuccessful) {
                    val uploadRate = (testData.size / 1024.0) / (duration / 1000.0)
                    Log.d(TAG, "HTTP upload completed: ${uploadRate} KB/s")
                    Pair(uploadRate, "Upload successful")
                } else {
                    Log.w(TAG, "HTTP upload failed: ${it.code}")
                    Pair(null, "Upload failed: ${it.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP upload test error", e)
            Pair(null, "Upload error: ${e.message}")
        }
    }

    private suspend fun runPingTest(): Pair<Double?, String> {
        return try {
            Log.d(TAG, "Starting ping test to $pingHost ...")

            val startTime = System.nanoTime()
            val address = InetAddress.getByName(pingHost)  // <-- use property
            val reachable = address.isReachable(5000)
            val endTime = System.nanoTime()

            if (reachable) {
                val responseTime = (endTime - startTime) / 1_000_000.0
                Log.d(TAG, "Ping test completed: ${responseTime}ms")
                Pair(responseTime, "Ping successful")
            } else {
                Log.w(TAG, "Ping test failed: host not reachable")
                Pair(null, "Ping failed: Host not reachable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping test error", e)
            Pair(null, "Ping error: ${e.message}")
        }
    }

    private suspend fun runDnsTest(): Pair<Double?, String> {
        return try {
            Log.d(TAG, "Starting DNS test for $dnsHost ...")

            val startTime = System.nanoTime()
            val addresses = InetAddress.getAllByName(dnsHost)  // <-- use property
            val endTime = System.nanoTime()

            val responseTime = (endTime - startTime) / 1_000_000.0
            Log.d(TAG, "DNS test completed: ${responseTime}ms, resolved ${addresses.size} addresses")
            Pair(responseTime, "DNS resolution successful (${addresses.size} addresses)")
        } catch (e: Exception) {
            Log.e(TAG, "DNS test error", e)
            Pair(null, "DNS error: ${e.message}")
        }
    }

    private suspend fun runWebTest(): Pair<Double?, String> {
        return try {
            Log.d(TAG, "Starting web test to $webTestUrl ...")
            var url = webTestUrl
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            webTestUrl = url
            val request = Request.Builder()
                .url(webTestUrl)  // <-- use property
                .get()
                .build()

            val startTime = System.nanoTime()
            val response = client.newCall(request).execute()
            val endTime = System.nanoTime()

            response.use {
                val responseTime = (endTime - startTime) / 1_000_000.0
                if (it.isSuccessful) {
                    Log.d(TAG, "Web test completed: ${responseTime}ms")
                    Pair(responseTime, "Web request successful")
                } else {
                    Log.w(TAG, "Web test failed: ${it.code}")
                    Pair(responseTime, "Web request failed: ${it.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Web test error", e)
            Pair(null, "Web error: ${e.message}")
        }
    }

    private suspend fun runSmsTest(): Pair<Double?, String> {
        return try {
            Log.d(TAG, "Starting SMS test to $smsTestNumber ...")

            if (!hasSmsPermission()) {
                return Pair(null, "SMS permission required")
            }
            val smsMgr = SmsManager.getDefault()
            val message = "Polaris Test SMS ${System.currentTimeMillis()}"

            val startTime = System.currentTimeMillis()
            smsMgr.sendTextMessage(smsTestNumber, null, message, null, null)  // <-- property
            val endTime = System.currentTimeMillis()

            val delay = (endTime - startTime).toDouble()
            Log.i(TAG, "SMS queued in ${delay}ms")
            Pair(delay, "SMS queued: ${delay}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "No SMS permission!", e)
            Pair(null, "SMS permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            Pair(null, "SMS error: ${e.message}")
        }
    }
    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }


}