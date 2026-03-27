package com.rokid.hud.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.hud.shared.protocol.Waypoint
import kotlin.math.*

interface NavigationCallback {
    fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>)
    fun onStepChanged(instruction: String, maneuver: String, distance: Double)
    fun onNavigationError(message: String)
    fun onArrived()
    fun onRerouting()
}

class NavigationManager(
    private val context: Context,
    private val callback: NavigationCallback
) {

    companion object {
        private const val TAG = "NavManager"
        private const val STEP_ADVANCE_RADIUS_M = 150.0
        private const val OFF_ROUTE_RADIUS_M = 80.0
        private const val REROUTE_COOLDOWN_MS = 15000L
        private const val ARRIVAL_RADIUS_M = 30.0
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var isNavigating = false; private set

    private var destLat = 0.0
    private var destLng = 0.0
    var steps: List<NavigationStep> = emptyList()
        private set
    private var routeWaypoints: List<Waypoint> = emptyList()
    var currentStepIndex = 0
        private set
    private var lastRerouteTime = 0L

    val currentInstruction: String
        get() = steps.getOrNull(currentStepIndex)?.instruction ?: ""

    val currentManeuver: String
        get() = steps.getOrNull(currentStepIndex)?.maneuver ?: ""

    val currentStepDistance: Double
        get() = steps.getOrNull(currentStepIndex)?.distance ?: 0.0

    fun startNavigation(destLat: Double, destLng: Double, currentLat: Double, currentLng: Double, initialRoute: RouteResult? = null) {
        this.destLat = destLat
        this.destLng = destLng
        isNavigating = true
        if (initialRoute != null) {
            applyRouteResult(initialRoute)
        } else {
            calculateRoute(currentLat, currentLng, destLat, destLng)
        }
    }

    fun stopNavigation() {
        isNavigating = false
        steps = emptyList()
        routeWaypoints = emptyList()
        currentStepIndex = 0
    }

    fun onLocationUpdate(lat: Double, lng: Double) {
        if (!isNavigating || steps.isEmpty()) return

        val distToDest = haversineM(lat, lng, destLat, destLng)
        if (distToDest < ARRIVAL_RADIUS_M && currentStepIndex >= steps.size - 2) {
            isNavigating = false
            mainHandler.post { callback.onArrived() }
            return
        }

        if (currentStepIndex < steps.size - 1) {
            val next = steps[currentStepIndex + 1]
            val distToNext = haversineM(lat, lng, next.locationLat, next.locationLng)
            if (distToNext < STEP_ADVANCE_RADIUS_M) {
                currentStepIndex++
                Log.i(TAG, "Advanced to step $currentStepIndex: ${steps[currentStepIndex].instruction}")
                mainHandler.post {
                    callback.onStepChanged(
                        steps[currentStepIndex].instruction,
                        steps[currentStepIndex].maneuver,
                        steps[currentStepIndex].distance
                    )
                }

                if (currentStepIndex < steps.size - 1) {
                    val afterNext = steps[currentStepIndex + 1]
                    val distAfter = haversineM(lat, lng, afterNext.locationLat, afterNext.locationLng)
                    if (distAfter < STEP_ADVANCE_RADIUS_M) {
                        currentStepIndex++
                        mainHandler.post {
                            callback.onStepChanged(
                                steps[currentStepIndex].instruction,
                                steps[currentStepIndex].maneuver,
                                steps[currentStepIndex].distance
                            )
                        }
                    }
                }
                return
            }
        }

        val nearestDist = nearestRouteDistance(lat, lng)
        if (nearestDist > OFF_ROUTE_RADIUS_M) {
            val now = System.currentTimeMillis()
            if (now - lastRerouteTime > REROUTE_COOLDOWN_MS) {
                lastRerouteTime = now
                Log.i(TAG, "Off route (${nearestDist.toInt()}m), rerouting...")
                mainHandler.post { callback.onRerouting() }
                calculateRoute(lat, lng, destLat, destLng)
            }
        }
    }

    private fun calculateRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double) {
        Thread {
            try {
                val routeMode = ProviderPrefs.getRouteMode(context)
                val result = if (routeMode == RouteMode.TRANSIT) {
                    val options = ProviderRegistry.transitRouteProvider(context)
                        .getTransitRouteOptions(fromLat, fromLng, toLat, toLng)
                    val index = TransitSelectionSession.selectedOptionIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0))
                    options.getOrNull(index)?.result?.route
                        ?: throw IllegalStateException("No transit route available.")
                } else {
                    ProviderRegistry.routeProvider(context)
                        .getRoute(fromLat, fromLng, toLat, toLng, routeMode)
                }
                applyRouteResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Route calculation failed", e)
                mainHandler.post { callback.onNavigationError(e.message ?: "Route failed") }
            }
        }.start()
    }

    private fun applyRouteResult(result: RouteResult) {
        routeWaypoints = result.waypoints
        steps = result.steps
        currentStepIndex = 0

        mainHandler.post {
            callback.onRouteCalculated(result.waypoints, result.totalDistance, result.totalDuration, result.steps)
            if (steps.isNotEmpty()) {
                callback.onStepChanged(steps[0].instruction, steps[0].maneuver, steps[0].distance)
            }
        }
    }

    fun getDistanceToNextStep(lat: Double, lng: Double): Double {
        if (!isNavigating || steps.isEmpty()) return -1.0
        val idx = if (currentStepIndex < steps.size - 1) currentStepIndex + 1 else currentStepIndex
        val step = steps[idx]
        return haversineM(lat, lng, step.locationLat, step.locationLng)
    }

    private fun nearestRouteDistance(lat: Double, lng: Double): Double {
        if (routeWaypoints.isEmpty()) return Double.MAX_VALUE
        return routeWaypoints.minOf { wp -> haversineM(lat, lng, wp.latitude, wp.longitude) }
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
