package com.anezium.rokidgmaps.phone

enum class TransitLegType {
    WALK,
    TRANSIT
}

data class TransitStop(
    val name: String,
    val lat: Double,
    val lng: Double
)

data class TransitLeg(
    val type: TransitLegType,
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val lineName: String? = null,
    val headsign: String? = null,
    val departureStop: TransitStop? = null,
    val arrivalStop: TransitStop? = null,
    val stopCount: Int? = null
)

data class TransitRouteResult(
    val legs: List<TransitLeg>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val transferCount: Int,
    val route: RouteResult
)

data class TransitRouteOption(
    val routeIndex: Int,
    val routeLabels: List<String>,
    val result: TransitRouteResult
)
