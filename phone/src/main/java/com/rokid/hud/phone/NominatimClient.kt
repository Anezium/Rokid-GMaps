package com.rokid.hud.phone

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lng: Double
)

object NominatimClient {

    private const val BASE_URL = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "RokidHudMaps/1.0"

    fun search(query: String, limit: Int = 6): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("$BASE_URL?q=$encoded&format=json&limit=$limit&addressdetails=0")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = obj.getString("lat").toDouble(),
                    lng = obj.getString("lon").toDouble()
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    fun searchNearby(
        query: String,
        lat: Double,
        lng: Double,
        radiusMeters: Int = 2500,
        limit: Int = 6
    ): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val latDelta = radiusMeters / 111_320.0
        val lngDelta = radiusMeters / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val left = lng - lngDelta
        val right = lng + lngDelta
        val top = lat + latDelta
        val bottom = lat - latDelta
        val url = URL(
            "$BASE_URL?q=$encoded&format=json&limit=$limit&addressdetails=0" +
                "&viewbox=$left,$top,$right,$bottom&bounded=1"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = obj.getString("lat").toDouble(),
                    lng = obj.getString("lon").toDouble()
                )
            }.sortedBy { haversineM(lat, lng, it.lat, it.lng) }
        } finally {
            conn.disconnect()
        }
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        return r * 2 * kotlin.math.asin(kotlin.math.sqrt(a))
    }
}
