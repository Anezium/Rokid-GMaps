package com.rokid.hud.phone

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*

class OverpassSpeedLimitClient {

    companion object {
        private const val TAG = "OverpassSpeed"
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val QUERY_INTERVAL_MS = 15_000L
        private const val QUERY_DISTANCE_M = 200.0
    }

    @Volatile private var cachedSpeedLimitKmh: Int = -1
    @Volatile private var lastQueryTime: Long = 0
    @Volatile private var lastQueryLat: Double = 0.0
    @Volatile private var lastQueryLng: Double = 0.0
    @Volatile private var querying: Boolean = false

    private val executor = Executors.newSingleThreadExecutor()

    fun getCachedSpeedLimit(lat: Double, lng: Double): Int {
        val now = System.currentTimeMillis()
        val dist = haversineM(lat, lng, lastQueryLat, lastQueryLng)
        if (!querying && (now - lastQueryTime > QUERY_INTERVAL_MS || dist > QUERY_DISTANCE_M)) {
            querying = true
            lastQueryTime = now
            lastQueryLat = lat
            lastQueryLng = lng
            executor.execute { querySpeedLimit(lat, lng) }
        }
        return cachedSpeedLimitKmh
    }

    private fun querySpeedLimit(lat: Double, lng: Double) {
        try {
            val query = "[out:json];way(around:30,$lat,$lng)[maxspeed];out tags;"
            val url = URL(OVERPASS_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "RokidHudMaps/1.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { it.write("data=$query") }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val elements = json.getJSONArray("elements")
                if (elements.length() > 0) {
                    val tags = elements.getJSONObject(0).getJSONObject("tags")
                    val maxspeed = tags.optString("maxspeed", "")
                    cachedSpeedLimitKmh = parseMaxspeed(maxspeed)
                } else {
                    cachedSpeedLimitKmh = -1
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Overpass query failed: ${e.message}")
        } finally {
            querying = false
        }
    }

    private fun parseMaxspeed(value: String): Int {
        if (value.isBlank()) return -1
        // Handles: "50", "30 mph", "60 km/h", "none"
        val trimmed = value.trim()
        if (trimmed.equals("none", true) || trimmed.equals("walk", true)) return -1

        val numMatch = Regex("(\\d+)").find(trimmed) ?: return -1
        val num = numMatch.groupValues[1].toIntOrNull() ?: return -1

        return if (trimmed.contains("mph", true)) {
            (num * 1.60934).toInt() // Convert mph to km/h
        } else {
            num // Already km/h or just a number (assumed km/h)
        }
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }
}
