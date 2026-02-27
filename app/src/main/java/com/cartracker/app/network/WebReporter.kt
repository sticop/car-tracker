package com.cartracker.app.network

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reports location data to the web tracking server.
 * Uses HttpURLConnection (no extra dependencies needed).
 * All calls are fire-and-forget with retry on failure.
 */
object WebReporter {
    private const val TAG = "WebReporter"

    // ─── Configuration ───────────────────────────────────────────────
    private const val API_BASE = "https://tracker.valoraconsulting.net/api.php"
    private const val API_KEY = "ctrk_9f8e7d6c5b4a3210_xK7mP2nQ"
    private const val DEVICE_ID = "default"
    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 5000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Report Current Location (every GPS fix) ─────────────────────
    fun reportLocation(
        latitude: Double,
        longitude: Double,
        speedKmh: Float,
        bearing: Float,
        altitude: Double,
        accuracy: Float,
        isMoving: Boolean,
        isCharging: Boolean,
        tripId: Long?
    ) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", DEVICE_ID)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("speedKmh", speedKmh.toDouble())
                    put("bearing", bearing.toDouble())
                    put("altitude", altitude)
                    put("accuracy", accuracy.toDouble())
                    put("isMoving", isMoving)
                    put("isCharging", isCharging)
                    put("tripId", tripId)
                    put("timestamp", System.currentTimeMillis())
                }
                post("location", json)
            } catch (e: Exception) {
                Log.w(TAG, "reportLocation failed: ${e.message}")
            }
        }
    }

    // ─── Report Trip Start ───────────────────────────────────────────
    fun reportTripStart(tripId: Long, latitude: Double, longitude: Double) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", DEVICE_ID)
                    put("tripId", tripId)
                    put("startTime", System.currentTimeMillis())
                    put("latitude", latitude)
                    put("longitude", longitude)
                }
                post("trip_start", json)
            } catch (e: Exception) {
                Log.w(TAG, "reportTripStart failed: ${e.message}")
            }
        }
    }

    // ─── Report Trip End ─────────────────────────────────────────────
    fun reportTripEnd(
        tripId: Long,
        distanceMeters: Double,
        maxSpeedKmh: Float,
        avgSpeedKmh: Float,
        durationMillis: Long,
        latitude: Double,
        longitude: Double
    ) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", DEVICE_ID)
                    put("tripId", tripId)
                    put("endTime", System.currentTimeMillis())
                    put("distanceMeters", distanceMeters)
                    put("maxSpeedKmh", maxSpeedKmh.toDouble())
                    put("avgSpeedKmh", avgSpeedKmh.toDouble())
                    put("durationMillis", durationMillis)
                    put("latitude", latitude)
                    put("longitude", longitude)
                }
                post("trip_end", json)
            } catch (e: Exception) {
                Log.w(TAG, "reportTripEnd failed: ${e.message}")
            }
        }
    }

    // ─── Report Trip Point ───────────────────────────────────────────
    fun reportTripPoint(
        tripId: Long,
        latitude: Double,
        longitude: Double,
        speedKmh: Float,
        bearing: Float,
        altitude: Double,
        accuracy: Float
    ) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", DEVICE_ID)
                    put("tripId", tripId)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("speedKmh", speedKmh.toDouble())
                    put("bearing", bearing.toDouble())
                    put("altitude", altitude)
                    put("accuracy", accuracy.toDouble())
                    put("timestamp", System.currentTimeMillis())
                }
                post("trip_point", json)
            } catch (e: Exception) {
                Log.w(TAG, "reportTripPoint failed: ${e.message}")
            }
        }
    }

    // ─── HTTP POST Helper ────────────────────────────────────────────
    private fun post(action: String, json: JSONObject) {
        val url = URL("$API_BASE?action=$action")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", API_KEY)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "API $action returned $responseCode")
            }
        } finally {
            conn.disconnect()
        }
    }
}
