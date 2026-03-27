package com.rokid.hud.phone

import android.content.Context

enum class RouteMode(
    val prefValue: String,
    val label: String,
    val googleTravelMode: String,
    val osrmProfile: String
) {
    DRIVE("drive", "Drive", "DRIVE", "driving"),
    WALK("walk", "Walk", "WALK", "walking"),
    TRANSIT("transit", "Transit", "TRANSIT", "");

    companion object {
        fun fromPrefValue(value: String?): RouteMode =
            entries.firstOrNull { it.prefValue == value } ?: DRIVE
    }
}

object ProviderPrefs {
    private const val PREFS_NAME = "rokid_gmaps_providers"
    private const val KEY_GOOGLE_API_KEY = "google_api_key"
    private const val KEY_USE_GOOGLE_SEARCH = "use_google_search"
    private const val KEY_USE_GOOGLE_ROUTES = "use_google_routes"
    private const val KEY_ROUTE_MODE = "route_mode"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGoogleApiKey(context: Context): String =
        prefs(context).getString(KEY_GOOGLE_API_KEY, "")?.trim().orEmpty()

    fun setGoogleApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GOOGLE_API_KEY, value.trim()).apply()
    }

    fun useGoogleSearch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_GOOGLE_SEARCH, false)

    fun setUseGoogleSearch(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_GOOGLE_SEARCH, value).apply()
    }

    fun useGoogleRoutes(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_GOOGLE_ROUTES, false)

    fun setUseGoogleRoutes(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_GOOGLE_ROUTES, value).apply()
    }

    fun getRouteMode(context: Context): RouteMode =
        RouteMode.fromPrefValue(prefs(context).getString(KEY_ROUTE_MODE, RouteMode.DRIVE.prefValue))

    fun setRouteMode(context: Context, routeMode: RouteMode) {
        prefs(context).edit().putString(KEY_ROUTE_MODE, routeMode.prefValue).apply()
    }

    fun currentSearchProviderLabel(context: Context): String =
        if (useGoogleSearch(context)) "Google" else "OSM"

    fun currentRouteProviderLabel(context: Context): String =
        if (useGoogleRoutes(context)) "Google" else "OSRM"

    fun currentRouteModeLabel(context: Context): String =
        getRouteMode(context).label
}
