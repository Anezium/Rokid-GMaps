package com.rokid.hud.phone

interface TransitRouteProvider {
    fun getTransitRouteOptions(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): List<TransitRouteOption>

    fun getTransitRoute(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): TransitRouteResult =
        getTransitRouteOptions(fromLat, fromLng, toLat, toLng).firstOrNull()?.result
            ?: throw IllegalStateException("No transit routes available.")
}

object NoOpTransitRouteProvider : TransitRouteProvider {
    override fun getTransitRouteOptions(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): List<TransitRouteOption> {
        throw UnsupportedOperationException("Transit routing is not implemented yet.")
    }

    override fun getTransitRoute(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): TransitRouteResult {
        throw UnsupportedOperationException("Transit routing is not implemented yet.")
    }
}

class GoogleTransitRouteProvider(
    private val apiKey: String
) : TransitRouteProvider {
    override fun getTransitRouteOptions(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): List<TransitRouteOption> {
        return GoogleRoutesClient(apiKey).getTransitRouteOptions(fromLat, fromLng, toLat, toLng)
    }

    override fun getTransitRoute(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): TransitRouteResult {
        return GoogleRoutesClient(apiKey).getTransitRoute(fromLat, fromLng, toLat, toLng)
    }
}
