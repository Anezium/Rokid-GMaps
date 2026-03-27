package com.rokid.hud.phone

import android.content.Context

interface PlaceSearchProvider {
    fun search(query: String, limit: Int = 6): List<SearchResult>
    fun searchNearby(query: String, lat: Double, lng: Double, radiusMeters: Int = 2500, limit: Int = 6): List<SearchResult>
}

interface NavigationRouteProvider {
    fun getRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, routeMode: RouteMode): RouteResult
}

object OsmPlaceSearchProvider : PlaceSearchProvider {
    override fun search(query: String, limit: Int): List<SearchResult> =
        NominatimClient.search(query, limit)

    override fun searchNearby(query: String, lat: Double, lng: Double, radiusMeters: Int, limit: Int): List<SearchResult> =
        NominatimClient.searchNearby(query, lat, lng, radiusMeters, limit)
}

object OsrmNavigationRouteProvider : NavigationRouteProvider {
    override fun getRoute(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, routeMode: RouteMode): RouteResult =
        OsrmClient.getRoute(fromLat, fromLng, toLat, toLng, routeMode)
}

object ProviderRegistry {
    fun placeSearchProvider(context: Context): PlaceSearchProvider {
        val apiKey = ProviderPrefs.getGoogleApiKey(context)
        return if (ProviderPrefs.useGoogleSearch(context)) {
            require(apiKey.isNotBlank()) { "Google API key missing in settings." }
            GooglePlacesClient(apiKey)
        } else {
            OsmPlaceSearchProvider
        }
    }

    fun routeProvider(context: Context): NavigationRouteProvider {
        val apiKey = ProviderPrefs.getGoogleApiKey(context)
        return if (ProviderPrefs.useGoogleRoutes(context)) {
            require(apiKey.isNotBlank()) { "Google API key missing in settings." }
            GoogleRoutesClient(apiKey)
        } else {
            OsrmNavigationRouteProvider
        }
    }

    fun transitRouteProvider(context: Context): TransitRouteProvider {
        val apiKey = ProviderPrefs.getGoogleApiKey(context)
        return if (ProviderPrefs.useGoogleRoutes(context)) {
            require(apiKey.isNotBlank()) { "Google API key missing in settings." }
            GoogleTransitRouteProvider(apiKey)
        } else {
            NoOpTransitRouteProvider
        }
    }

    fun validateGoogleSelection(context: Context): String? {
        val needsGoogle = ProviderPrefs.useGoogleSearch(context) || ProviderPrefs.useGoogleRoutes(context)
        if (needsGoogle && ProviderPrefs.getGoogleApiKey(context).isBlank()) {
            return "Google API key missing in settings."
        }
        if (ProviderPrefs.getRouteMode(context) == RouteMode.TRANSIT && !ProviderPrefs.useGoogleRoutes(context)) {
            return "Transit mode currently requires Google routes."
        }
        return null
    }
}
