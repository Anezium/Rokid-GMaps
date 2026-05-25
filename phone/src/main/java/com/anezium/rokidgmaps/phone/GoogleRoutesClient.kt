package com.anezium.rokidgmaps.phone

import com.anezium.rokidgmaps.shared.protocol.Waypoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

class GoogleRoutesClient(private val apiKey: String) : NavigationRouteProvider {
    companion object {
        private const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
        private const val STANDARD_FIELD_MASK =
            "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline," +
                "routes.legs.steps.distanceMeters," +
                "routes.legs.steps.travelMode," +
                "routes.legs.steps.navigationInstruction.instructions," +
                "routes.legs.steps.navigationInstruction.maneuver," +
                "routes.legs.steps.endLocation"
        private const val TRANSIT_FIELD_MASK =
            "routes.distanceMeters,routes.duration,routes.routeLabels,routes.polyline.encodedPolyline," +
                "routes.legs.steps.distanceMeters," +
                "routes.legs.steps.travelMode," +
                "routes.legs.steps.navigationInstruction.instructions," +
                "routes.legs.steps.navigationInstruction.maneuver," +
                "routes.legs.steps.transitDetails," +
                "routes.legs.steps.startLocation," +
                "routes.legs.steps.endLocation"
        private const val USER_AGENT = "RokidGMaps/1.0"
    }

    override fun getRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, routeMode: RouteMode): RouteResult {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Google API key missing.")
        }
        if (routeMode == RouteMode.TRANSIT) {
            return getTransitRoute(fromLat, fromLng, toLat, toLng).route
        }
        return fetchRoute(fromLat, fromLng, toLat, toLng, routeMode)
    }

    fun getTransitRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): TransitRouteResult {
        return getTransitRouteOptions(fromLat, fromLng, toLat, toLng).firstOrNull()?.result
            ?: throw IllegalStateException("No transit route returned by Google.")
    }

    fun getTransitRouteOptions(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): List<TransitRouteOption> {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Google API key missing.")
        }
        val response = fetchRawRouteResponse(fromLat, fromLng, toLat, toLng, RouteMode.TRANSIT, computeAlternatives = true)
        return parseTransitRouteOptions(response)
    }

    private fun fetchRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, routeMode: RouteMode): RouteResult {
        val response = fetchRawRouteResponse(fromLat, fromLng, toLat, toLng, routeMode)
        return parseRoute(response, routeMode)
    }

    private fun fetchRawRouteResponse(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        routeMode: RouteMode,
        computeAlternatives: Boolean = false
    ): String {
        val body = JSONObject().apply {
            put("origin", waypoint(fromLat, fromLng))
            put("destination", waypoint(toLat, toLng))
            put("travelMode", routeMode.googleTravelMode)
            if (routeMode == RouteMode.DRIVE) {
                put("routingPreference", "TRAFFIC_UNAWARE")
            }
            if (routeMode == RouteMode.TRANSIT) {
                put("computeAlternativeRoutes", computeAlternatives)
                put("transitPreferences", JSONObject().apply {
                    put("routingPreference", "LESS_WALKING")
                })
            }
            put("polylineQuality", "OVERVIEW")
            put("polylineEncoding", "ENCODED_POLYLINE")
            put("languageCode", Locale.getDefault().toLanguageTag())
            put("units", "METRIC")
        }

        val conn = openPostJson(if (routeMode == RouteMode.TRANSIT) TRANSIT_FIELD_MASK else STANDARD_FIELD_MASK)
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
            response
        } finally {
            conn.disconnect()
        }
    }

    private fun openPostJson(fieldMask: String): HttpURLConnection {
        val conn = URL(ROUTES_URL).openConnection() as HttpURLConnection
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

    private fun parseRoute(response: String, routeMode: RouteMode): RouteResult {
        val root = JSONObject(response)
        val routes = root.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            throw IllegalStateException("No route returned by Google.")
        }

        val route = routes.getJSONObject(0)
        return parseRouteObject(route, routeMode)
    }

    private fun parseRouteObject(route: JSONObject, routeMode: RouteMode): RouteResult {
        val totalDistance = route.optDouble("distanceMeters", 0.0)
        val totalDuration = parseDurationSeconds(route.optString("duration", ""))
        val overviewPolyline = route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
        val waypoints = decodePolyline(overviewPolyline).map { Waypoint(it.first, it.second) }

        val steps = mutableListOf<NavigationStep>()
        val legs = route.optJSONArray("legs") ?: JSONArray()
        if (legs.length() > 0) {
            val stepArray = legs.getJSONObject(0).optJSONArray("steps") ?: JSONArray()
            for (index in 0 until stepArray.length()) {
                val item = stepArray.optJSONObject(index) ?: continue
                val navInstruction = item.optJSONObject("navigationInstruction")
                val travelMode = item.optString("travelMode")
                val endLocation = item.optJSONObject("endLocation")
                    ?.optJSONObject("latLng")
                val instruction = navInstruction?.optString("instructions").orEmpty().ifBlank { "Continue" }
                val googleManeuver = navInstruction?.optString("maneuver").orEmpty()
                val transitInstruction = if (routeMode == RouteMode.TRANSIT || travelMode.equals("TRANSIT", true)) {
                    buildTransitInstruction(item)
                } else null
                val lat = endLocation?.optDouble("latitude", Double.NaN) ?: Double.NaN
                val lng = endLocation?.optDouble("longitude", Double.NaN) ?: Double.NaN
                steps.add(
                    NavigationStep(
                        instruction = transitInstruction ?: instruction,
                        maneuver = if (travelMode.equals("TRANSIT", true)) "transit" else mapManeuver(googleManeuver),
                        distance = item.optDouble("distanceMeters", 0.0),
                        duration = 0.0,
                        locationLat = if (lat.isFinite()) lat else 0.0,
                        locationLng = if (lng.isFinite()) lng else 0.0
                    )
                )
            }
        }

        return RouteResult(
            waypoints = waypoints,
            steps = steps,
            totalDistance = totalDistance,
            totalDuration = totalDuration
        )
    }

    private fun parseTransitRouteOptions(response: String): List<TransitRouteOption> {
        val root = JSONObject(response)
        val routes = root.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            throw IllegalStateException("No transit route returned by Google.")
        }

        return buildList {
            for (index in 0 until routes.length()) {
                val routeJson = routes.optJSONObject(index) ?: continue
                val labels = routeJson.optJSONArray("routeLabels")?.toStringList().orEmpty()
                add(
                    TransitRouteOption(
                        routeIndex = index,
                        routeLabels = labels,
                        result = parseTransitRouteObject(routeJson)
                    )
                )
            }
        }
    }

    private fun parseTransitRouteObject(routeJson: JSONObject): TransitRouteResult {
        val route = parseRouteObject(routeJson, RouteMode.TRANSIT)
        val totalDistance = routeJson.optDouble("distanceMeters", 0.0)
        val totalDuration = parseDurationSeconds(routeJson.optString("duration", ""))
        val transitLegs = mutableListOf<TransitLeg>()
        val routeLegs = routeJson.optJSONArray("legs") ?: JSONArray()
        for (routeLegIndex in 0 until routeLegs.length()) {
            val routeLeg = routeLegs.optJSONObject(routeLegIndex) ?: continue
            val steps = routeLeg.optJSONArray("steps") ?: JSONArray()
            for (stepIndex in 0 until steps.length()) {
                val step = steps.optJSONObject(stepIndex) ?: continue
                val distanceMeters = step.optDouble("distanceMeters", 0.0)
                val durationSeconds = parseDurationSeconds(step.optString("staticDuration", step.optString("duration", "")))
                if (step.optString("travelMode").equals("TRANSIT", true)) {
                    val details = step.optJSONObject("transitDetails") ?: continue
                    val stopDetails = details.optJSONObject("stopDetails")
                    val departureStop = stopDetails?.optJSONObject("departureStop")?.let { stop ->
                        val latLng = stop.optJSONObject("location")?.optJSONObject("latLng")
                        TransitStop(
                            name = stop.optString("name", "Departure stop"),
                            lat = latLng?.optDouble("latitude", 0.0) ?: 0.0,
                            lng = latLng?.optDouble("longitude", 0.0) ?: 0.0
                        )
                    }
                    val arrivalStop = stopDetails?.optJSONObject("arrivalStop")?.let { stop ->
                        val latLng = stop.optJSONObject("location")?.optJSONObject("latLng")
                        TransitStop(
                            name = stop.optString("name", "Arrival stop"),
                            lat = latLng?.optDouble("latitude", 0.0) ?: 0.0,
                            lng = latLng?.optDouble("longitude", 0.0) ?: 0.0
                        )
                    }
                    val line = details.optJSONObject("transitLine")
                    val lineName = line?.optString("nameShort").takeUnless { it.isNullOrBlank() }
                        ?: line?.optString("name").takeUnless { it.isNullOrBlank() }
                        ?: line?.optJSONObject("vehicle")?.optJSONObject("name")?.optString("text")
                    transitLegs += TransitLeg(
                        type = TransitLegType.TRANSIT,
                        instruction = buildTransitInstruction(step) ?: "Take transit",
                        distanceMeters = distanceMeters,
                        durationSeconds = durationSeconds,
                        lineName = lineName,
                        headsign = details.optString("headsign").takeUnless { it.isBlank() },
                        departureStop = departureStop,
                        arrivalStop = arrivalStop,
                        stopCount = stopDetails?.optInt("stopCount", -1)?.takeIf { it >= 0 }
                    )
                } else {
                    val instruction = step.optJSONObject("navigationInstruction")?.optString("instructions").orEmpty().ifBlank { "Walk" }
                    transitLegs += TransitLeg(
                        type = TransitLegType.WALK,
                        instruction = instruction,
                        distanceMeters = distanceMeters,
                        durationSeconds = durationSeconds
                    )
                }
            }
        }

        return TransitRouteResult(
            legs = transitLegs,
            totalDistanceMeters = totalDistance,
            totalDurationSeconds = totalDuration,
            transferCount = (transitLegs.count { it.type == TransitLegType.TRANSIT } - 1).coerceAtLeast(0),
            route = route
        )
    }

    private fun waypoint(lat: Double, lng: Double): JSONObject =
        JSONObject().apply {
            put("location", JSONObject().apply {
                put("latLng", JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lng)
                })
            })
        }

    private fun parseDurationSeconds(duration: String): Double {
        if (!duration.endsWith("s")) {
            return 0.0
        }
        return duration.removeSuffix("s").toDoubleOrNull() ?: 0.0
    }

    private fun mapManeuver(raw: String): String {
        val maneuver = raw.uppercase(Locale.US)
        return when {
            maneuver.contains("LEFT") && maneuver.contains("UTURN") -> "uturn_left"
            maneuver.contains("RIGHT") && maneuver.contains("UTURN") -> "uturn_right"
            maneuver.contains("UTURN") -> "uturn"
            maneuver.contains("ARRIVE") -> "arrive"
            maneuver.contains("DEPART") -> "depart"
            maneuver.contains("FORK") -> if (maneuver.contains("LEFT")) "fork_left" else if (maneuver.contains("RIGHT")) "fork_right" else "fork"
            maneuver.contains("RAMP") -> if (maneuver.contains("LEFT")) "ramp_left" else if (maneuver.contains("RIGHT")) "ramp_right" else "ramp"
            maneuver.contains("MERGE") -> "merge"
            maneuver.contains("LEFT") -> "left"
            maneuver.contains("RIGHT") -> "right"
            else -> "straight"
        }
    }

    private fun buildTransitInstruction(step: JSONObject): String? {
        val details = step.optJSONObject("transitDetails") ?: return null
        val stopDetails = details.optJSONObject("stopDetails")
        val departure = stopDetails?.optJSONObject("departureStop")?.optString("name").orEmpty()
        val arrival = stopDetails?.optJSONObject("arrivalStop")?.optString("name").orEmpty()
        val stopCount = stopDetails?.optInt("stopCount", -1) ?: -1
        val headsign = details.optString("headsign", "")

        val line = details.optJSONObject("transitLine")
        val shortName = line?.optString("nameShort").orEmpty()
        val longName = line?.optString("name").orEmpty()
        val vehicleName = line?.optJSONObject("vehicle")?.optJSONObject("name")?.optString("text").orEmpty()
        val lineLabel = listOf(shortName, longName, vehicleName).firstOrNull { it.isNotBlank() }.orEmpty()

        val parts = mutableListOf<String>()
        if (lineLabel.isNotBlank()) {
            parts += "Take $lineLabel"
        } else {
            parts += "Take transit"
        }
        if (headsign.isNotBlank()) {
            parts += "toward $headsign"
        }
        if (departure.isNotBlank()) {
            parts += "from $departure"
        }
        if (arrival.isNotBlank()) {
            parts += "to $arrival"
        }
        if (stopCount > 0) {
            parts += "($stopCount stops)"
        }
        return parts.joinToString(" ")
    }

    private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        if (encoded.isBlank()) {
            return emptyList()
        }
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            val latResult = decodeNext(encoded, index)
            lat += latResult.first
            index = latResult.second

            val lngResult = decodeNext(encoded, index)
            lng += lngResult.first
            index = lngResult.second

            points += (lat / 1E5) to (lng / 1E5)
        }

        return thin(points)
    }

    private fun decodeNext(encoded: String, startIndex: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var index = startIndex
        var value: Int
        do {
            value = encoded[index++].code - 63
            result = result or ((value and 0x1f) shl shift)
            shift += 5
        } while (value >= 0x20 && index < encoded.length)

        val delta = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        return delta to index
    }

    private fun thin(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size <= 500) {
            return points
        }
        val stride = (points.size / 500.0).roundToInt().coerceAtLeast(1)
        val sampled = points.filterIndexed { index, _ -> index % stride == 0 }.toMutableList()
        if (sampled.lastOrNull() != points.last()) {
            sampled += points.last()
        }
        return sampled
    }

    private fun extractGoogleError(body: String, status: Int): String {
        return try {
            val error = JSONObject(body).optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() } ?: "Google Routes error ($status)"
        } catch (_: Exception) {
            "Google Routes error ($status)"
        }
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
