package com.anezium.rokidgmaps.phone

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class GooglePlacesClient(private val apiKey: String) : PlaceSearchProvider {
    companion object {
        private const val SEARCH_TEXT_URL = "https://places.googleapis.com/v1/places:searchText"
        private const val FIELD_MASK = "places.displayName,places.formattedAddress,places.location"
        private const val USER_AGENT = "RokidGMaps/1.0"
    }

    override fun search(query: String, limit: Int): List<SearchResult> {
        return executeSearchText(query, limit, null, null, null)
    }

    override fun searchNearby(query: String, lat: Double, lng: Double, radiusMeters: Int, limit: Int): List<SearchResult> {
        return executeSearchText(query, limit, lat, lng, radiusMeters)
    }

    private fun executeSearchText(
        query: String,
        limit: Int,
        lat: Double?,
        lng: Double?,
        radiusMeters: Int?
    ): List<SearchResult> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Google API key missing.")
        }

        val body = JSONObject().apply {
            put("textQuery", query)
            put("pageSize", limit.coerceIn(1, 20))
            put("languageCode", Locale.getDefault().toLanguageTag())
            val regionCode = Locale.getDefault().country
            if (regionCode.isNotBlank()) {
                put("regionCode", regionCode)
            }
            if (lat != null && lng != null && radiusMeters != null) {
                put("locationBias", createCircleBias(lat, lng, radiusMeters))
                put("rankPreference", "DISTANCE")
            }
        }

        val conn = openPostJson(SEARCH_TEXT_URL, FIELD_MASK)
        return try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.readText().orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException(extractGoogleError(response, status))
            }
            parsePlaces(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun createCircleBias(lat: Double, lng: Double, radiusMeters: Int): JSONObject =
        JSONObject().apply {
            put("circle", JSONObject().apply {
                put("center", JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lng)
                })
                put("radius", radiusMeters)
            })
        }

    private fun parsePlaces(response: String): List<SearchResult> {
        val root = JSONObject(response)
        val places = root.optJSONArray("places") ?: JSONArray()
        return (0 until places.length()).mapNotNull { index ->
            val place = places.optJSONObject(index) ?: return@mapNotNull null
            val location = place.optJSONObject("location") ?: return@mapNotNull null
            val displayName = place.optJSONObject("displayName")?.optString("text").orEmpty()
            val address = place.optString("formattedAddress", "").trim()
            val lat = location.optDouble("latitude", Double.NaN)
            val lng = location.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lng.isFinite()) {
                return@mapNotNull null
            }
            SearchResult(
                displayName = listOf(displayName, address)
                    .filter { it.isNotBlank() }
                    .joinToString(", "),
                lat = lat,
                lng = lng
            )
        }
    }

    private fun openPostJson(url: String, fieldMask: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("X-Goog-Api-Key", apiKey)
        conn.setRequestProperty("X-Goog-FieldMask", fieldMask)
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    private fun extractGoogleError(body: String, status: Int): String {
        return try {
            val error = JSONObject(body).optJSONObject("error")
            val message = error?.optString("message").orEmpty()
            when {
                message.contains("Places API (New)", ignoreCase = true) &&
                    message.contains("disabled", ignoreCase = true) ->
                    "Google search unavailable: enable Places API (New) in Google Cloud."
                message.contains("API key", ignoreCase = true) ->
                    "Google search unavailable: API key rejected by Places API."
                message.isNotBlank() -> message
                else -> "Google Places error ($status)"
            }
        } catch (_: Exception) {
            "Google Places error ($status)"
        }
    }
}
