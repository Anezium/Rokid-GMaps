package com.anezium.rokidgmaps.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Binder
import android.os.PowerManager
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.anezium.rokidgmaps.shared.protocol.*
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Base64
import java.util.UUID
import com.anezium.rokidgmaps.shared.cache.DiskTileCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class HudStreamingService : Service() {

    companion object {
        private const val TAG = "HudStreaming"
        private const val SERVICE_NAME = "RokidHudSPP"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_STREAMING = "com.anezium.rokidgmaps.phone.action.STOP_STREAMING"
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val MAX_TILE_BYTES = 512 * 1024 // 512KB cap to avoid OOM on bad/corrupt responses
        private const val LANDMARK_REFRESH_MS = 180_000L
        private const val LANDMARK_REFRESH_DISTANCE_M = 400.0
        private const val LANDMARK_RADIUS_METERS = 1800
        private const val MAX_LANDMARKS = 10
        private const val GOOGLE_TILE_SESSION_URL = "https://tile.googleapis.com/v1/createSession"
        private const val GOOGLE_TILE_URL = "https://tile.googleapis.com/v1/2dtiles/%d/%d/%d"
        private const val GOOGLE_TILE_SESSION_REFRESH_MARGIN_MS = 60 * 60 * 1000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): HudStreamingService = this@HudStreamingService
    }

    private val binder = LocalBinder()
    private var serverSocket: BluetoothServerSocket? = null
    private val clients = CopyOnWriteArrayList<BufferedWriter>()
    private val clientSessions = CopyOnWriteArrayList<ClientSession>()
    private val tileExecutor = Executors.newFixedThreadPool(4)
    private val landmarkExecutor = Executors.newSingleThreadExecutor()
    private val TILE_URLS = arrayOf(
        "https://basemaps.cartocdn.com/dark_all/%d/%d/%d@2x.png",
        "https://basemaps.cartocdn.com/dark_all/%d/%d/%d.png",
        "https://tile.openstreetmap.org/%d/%d/%d.png"
    )
    private val USER_AGENT = "RokidHudMaps/1.0 (Phone proxy)"

    private data class ClientSession(val socket: BluetoothSocket, val writer: BufferedWriter)
    private data class LandmarkQuery(val query: String, val category: String, val priority: Int)

    private val landmarkQueries = listOf(
        LandmarkQuery("tourist attraction", "landmark", 90),
        LandmarkQuery("train station", "station", 80),
        LandmarkQuery("metro station", "metro", 70),
        LandmarkQuery("McDonald's", "food", 45)
    )
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    @Volatile private var running = false

    private var lastLat = 0.0
    private var lastLng = 0.0

    private var cachedSettings: SettingsMessage? = null
    private var cachedWifiCreds: WifiCredsMessage? = null
    private var cachedPois: PoisMessage? = null
    private val speedLimitClient = OverpassSpeedLimitClient()
    private var diskTileCache: DiskTileCache? = null
    @Volatile private var landmarkRefreshRunning = false
    private var lastLandmarkRefreshMs = 0L
    private var lastLandmarkLat = Double.NaN
    private var lastLandmarkLng = Double.NaN
    private val googleTileSessionLock = Any()
    @Volatile private var googleTileSession: String? = null
    @Volatile private var googleTileSessionExpiryMs: Long = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    var navigationManager: NavigationManager? = null
    var uiCallback: NavigationCallback? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_STREAMING) {
            stopNavigation()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) {
            running = true
            diskTileCache = DiskTileCache(applicationContext)
            acquireWakeLock()
            initNavigation()
            startBluetoothServer()
            startLocationUpdates()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            Log.e(TAG, "PowerManager null — WakeLock not acquired")
            return
        }
        val tag = "${packageName}:streaming"
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            try {
                acquire() // Hold until release() in onDestroy — keeps CPU running when screen off
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock acquire failed: ${e.message}")
            }
        }
        if (wakeLock?.isHeld == true) {
            Log.i(TAG, "WakeLock acquired — maps keep updating when screen is off")
        } else {
            Log.e(TAG, "WakeLock not held after acquire")
        }
    }

    override fun onDestroy() {
        running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        navigationManager?.stopNavigation()
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        try { serverSocket?.close() } catch (_: Exception) {}
        for (s in clientSessions) {
            try { s.socket.close() } catch (_: Exception) {}
        }
        clientSessions.clear()
        for (w in clients) { try { w.close() } catch (_: Exception) {} }
        clients.clear()
        tileExecutor.shutdownNow()
        landmarkExecutor.shutdownNow()
        diskTileCache?.shutdown()
        super.onDestroy()
    }

    private fun initNavigation() {
        navigationManager = NavigationManager(applicationContext, object : NavigationCallback {
            override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>) {
                sendNavMode(previewActive = false)
                sendRoute(waypoints, totalDistance, totalDuration)
                sendStepsList()
                uiCallback?.onRouteCalculated(waypoints, totalDistance, totalDuration, steps)
            }
            override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
                sendStep(instruction, maneuver, distance)
                sendStepsList()
                uiCallback?.onStepChanged(instruction, maneuver, distance)
            }
            override fun onNavigationError(message: String) {
                uiCallback?.onNavigationError(message)
            }
            override fun onArrived() {
                sendStep("You have arrived!", "arrive", 0.0)
                uiCallback?.onArrived()
            }
            override fun onRerouting() {
                uiCallback?.onRerouting()
            }
        })
    }

    fun startNavigation(destLat: Double, destLng: Double, initialRoute: RouteResult? = null) {
        sendNavMode(previewActive = false)
        navigationManager?.startNavigation(destLat, destLng, lastLat, lastLng, initialRoute)
    }

    fun showRoutePreview(result: RouteResult) {
        sendNavMode(previewActive = true)
        sendStep("", "", 0.0)
        sendRoute(result.waypoints, result.totalDistance, result.totalDuration)
        val stepInfos = result.steps.map { StepInfo(it.instruction, it.maneuver, it.distance) }
        broadcast(ProtocolCodec.encodeStepsList(StepsListMessage(stepInfos, 0)))
    }

    fun stopNavigation() {
        navigationManager?.stopNavigation()
        sendNavMode(previewActive = false)
        sendStep("", "", 0.0)
        sendRoute(emptyList(), 0.0, 0.0)
        broadcast(ProtocolCodec.encodeStepsList(StepsListMessage(emptyList(), 0)))
    }

    fun getLastLocation(): Pair<Double, Double> = Pair(lastLat, lastLng)

    fun sendSettings(
        ttsEnabled: Boolean, useImperial: Boolean = false,
        useMiniMap: Boolean = false, miniMapStyle: String = "strip",
        routeMode: String = "drive",
        streamNotifications: Boolean = true, showUpcomingSteps: Boolean = false,
        showTurnAlert: Boolean = false, tileCacheSizeMb: Int = 100,
        showSpeed: Boolean = true, showSpeedLimit: Boolean = true,
        showLandmarks: Boolean = true
    ) {
        val msg = SettingsMessage(ttsEnabled, useImperial, useMiniMap, miniMapStyle, routeMode, streamNotifications, showUpcomingSteps, showTurnAlert, tileCacheSizeMb, showSpeed, showSpeedLimit, showLandmarks)
        cachedSettings = msg
        broadcast(ProtocolCodec.encodeSettings(msg))
        if (!showLandmarks) {
            sendPois(emptyList())
        }
    }

    fun sendWifiCreds(ssid: String, passphrase: String, enabled: Boolean) {
        val msg = WifiCredsMessage(ssid, passphrase, enabled)
        cachedWifiCreds = msg
        broadcast(ProtocolCodec.encodeWifiCreds(msg))
    }

    private val apkHandler = Handler(android.os.Looper.getMainLooper())

    /** Send an APK file to connected glasses in chunks over Bluetooth. Callbacks run on main thread. */
    fun sendApkToGlasses(
        uri: Uri,
        onProgress: ((sentChunks: Int, totalChunks: Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (clients.isEmpty()) {
            apkHandler.post { onError?.invoke("No glasses connected") ?: Unit }
            return
        }
        Thread {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open APK file")
                val totalSize = pfd.statSize
                pfd.close()
                if (totalSize <= 0) throw IllegalStateException("Invalid APK size")
                val CHUNK_RAW = 3072
                val totalChunks = ((totalSize + CHUNK_RAW - 1) / CHUNK_RAW).toInt()
                broadcast(ProtocolCodec.encodeApkStart(ApkStartMessage(totalSize, totalChunks)))
                apkHandler.post { onProgress?.invoke(0, totalChunks) }
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(CHUNK_RAW)
                    var sent = 0
                    var index = 0
                    while (running && index < totalChunks) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = if (read < buffer.size) buffer.copyOf(read) else buffer
                        val b64 = Base64.getEncoder().encodeToString(chunk)
                        broadcast(ProtocolCodec.encodeApkChunk(ApkChunkMessage(index, b64)))
                        sent++
                        index++
                        if (sent % 20 == 0 || index == totalChunks) {
                            val s = sent
                            val t = totalChunks
                            apkHandler.post { onProgress?.invoke(s, t) }
                        }
                    }
                    broadcast(ProtocolCodec.encodeApkEnd())
                    apkHandler.post {
                        onProgress?.invoke(totalChunks, totalChunks)
                        onDone?.invoke()
                    }
                } ?: throw IllegalStateException("Cannot read APK")
            } catch (e: Exception) {
                Log.w(TAG, "sendApkToGlasses failed", e)
                apkHandler.post { onError?.invoke(e.message ?: "Unknown error") ?: Unit }
            }
        }.start()
    }

    fun clearTileCache() { diskTileCache?.clear() }
    fun tileCacheSizeBytes(): Long = diskTileCache?.sizeBytes() ?: 0L
    fun updateTileCacheSize(mb: Int) { diskTileCache?.updateMaxSize(mb) }

    fun sendNotification(title: String?, text: String?, packageName: String?) {
        broadcast(ProtocolCodec.encodeNotification(
            NotificationMessage(title, text, packageName, System.currentTimeMillis())
        ))
    }

    fun sendStep(instruction: String, maneuver: String, distance: Double) {
        broadcast(ProtocolCodec.encodeStep(StepMessage(instruction, maneuver, distance)))
    }

    fun sendStepsList() {
        val nav = navigationManager ?: return
        if (nav.steps.isEmpty()) return
        val stepInfos = nav.steps.map { StepInfo(it.instruction, it.maneuver, it.distance) }
        broadcast(ProtocolCodec.encodeStepsList(StepsListMessage(stepInfos, nav.currentStepIndex)))
    }

    fun sendRoute(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double) {
        broadcast(ProtocolCodec.encodeRoute(RouteMessage(waypoints, totalDistance, totalDuration)))
    }

    private fun sendPois(items: List<PoiInfo>) {
        val msg = PoisMessage(items)
        cachedPois = msg
        broadcast(ProtocolCodec.encodePois(msg))
    }

    fun sendNavMode(previewActive: Boolean) {
        broadcast(ProtocolCodec.encodeNavMode(NavModeMessage(previewActive)))
    }

    private fun resendCachedState(writer: BufferedWriter) {
        try {
            cachedSettings?.let {
                writer.write(ProtocolCodec.encodeSettings(it)); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent settings to new client")
            }
            cachedWifiCreds?.let {
                writer.write(ProtocolCodec.encodeWifiCreds(it)); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent wifi creds to new client")
            }
            cachedPois?.let {
                writer.write(ProtocolCodec.encodePois(it)); writer.newLine(); writer.flush()
                Log.i(TAG, "Re-sent POIs to new client")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-send cached state: ${e.message}")
        }
    }

    private fun broadcast(json: String) {
        val dead = mutableListOf<BufferedWriter>()
        for (writer in clients) {
            try {
                writer.write(json)
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Client write failed", e)
                dead.add(writer)
            }
        }
        clients.removeAll(dead.toSet())
    }

    private fun sendToClient(writer: BufferedWriter, json: String) {
        try {
            writer.write(json)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Send to client failed: ${e.message}")
        }
    }

    private fun handleTileRequest(z: Int, x: Int, y: Int, id: String, writer: BufferedWriter) {
        tileExecutor.execute {
            try {
                if (!running) return@execute
                var data: String? = null

                val googleApiKey = ProviderPrefs.getGoogleApiKey(applicationContext)
                val googleTileBytes = if (googleApiKey.isNotBlank()) {
                    fetchGoogleBlackRoadmapTile(z, x, y, googleApiKey)
                } else {
                    null
                }
                if (googleTileBytes != null) {
                    data = Base64.getEncoder().encodeToString(googleTileBytes)
                    diskTileCache?.put(z, x, y, googleTileBytes)
                } else {
                    // Check disk cache first for the fallback tile source.
                    val cached = diskTileCache?.get(z, x, y)
                    if (cached != null) {
                        data = Base64.getEncoder().encodeToString(cached)
                    } else {
                        for (template in TILE_URLS) {
                            try {
                                val url = URL(String.format(template, z, x, y))
                                val conn = url.openConnection() as HttpURLConnection
                                conn.setRequestProperty("User-Agent", USER_AGENT)
                                conn.connectTimeout = 8000
                                conn.readTimeout = 8000
                                if (conn.responseCode == 200) {
                                    val bytes = readBounded(conn.inputStream, MAX_TILE_BYTES)
                                    conn.disconnect()
                                    if (bytes.isNotEmpty()) {
                                        data = Base64.getEncoder().encodeToString(bytes)
                                        diskTileCache?.put(z, x, y, bytes)
                                    }
                                    break
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                Log.w(TAG, "Tile fetch $id failed: ${e.message}")
                            }
                        }
                    }
                }

                if (!running) return@execute
                if (!clients.contains(writer)) return@execute
                val resp = ProtocolCodec.encodeTileResp(TileResponseMessage(id = id, data = data))
                sendToClient(writer, resp)
            } catch (t: Throwable) {
                Log.e(TAG, "Tile handle $id error", t)
            }
        }
    }

    private fun fetchGoogleBlackRoadmapTile(z: Int, x: Int, y: Int, apiKey: String): ByteArray? {
        val session = getGoogleBlackRoadmapSession(apiKey) ?: return null
        return try {
            val url = URL(
                String.format(GOOGLE_TILE_URL, z, x, y) +
                    "?session=${URLEncoder.encode(session, "UTF-8")}" +
                    "&key=${URLEncoder.encode(apiKey, "UTF-8")}"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                if (conn.responseCode == 200) {
                    readBounded(conn.inputStream, MAX_TILE_BYTES).takeIf { it.isNotEmpty() }
                } else {
                    Log.w(TAG, "Google black tile failed: HTTP ${conn.responseCode}")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google black tile fetch failed: ${e.message}")
            null
        }
    }

    private fun getGoogleBlackRoadmapSession(apiKey: String): String? {
        val now = System.currentTimeMillis()
        googleTileSession?.let { cached ->
            if (now < googleTileSessionExpiryMs - GOOGLE_TILE_SESSION_REFRESH_MARGIN_MS) return cached
        }
        synchronized(googleTileSessionLock) {
            googleTileSession?.let { cached ->
                if (now < googleTileSessionExpiryMs - GOOGLE_TILE_SESSION_REFRESH_MARGIN_MS) return cached
            }
            return try {
                val conn = (URL("$GOOGLE_TILE_SESSION_URL?key=${URLEncoder.encode(apiKey, "UTF-8")}")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", USER_AGENT)
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                try {
                    val body = buildGoogleBlackRoadmapSessionBody().toString()
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode != 200) {
                        Log.w(TAG, "Google tile session failed: HTTP ${conn.responseCode}")
                        return null
                    }
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val session = json.optString("session")
                    if (session.isBlank()) return null
                    val expirySeconds = json.optString("expiry").toLongOrNull()
                    googleTileSession = session
                    googleTileSessionExpiryMs = expirySeconds?.times(1000L) ?: (now + 13L * 24L * 60L * 60L * 1000L)
                    session
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google tile session error: ${e.message}")
                null
            }
        }
    }

    private fun buildGoogleBlackRoadmapSessionBody(): JSONObject {
        return JSONObject()
            .put("mapType", "roadmap")
            .put("language", "fr-FR")
            .put("region", "FR")
            .put("imageFormat", "png")
            .put("scale", "scaleFactor2x")
            .put("styles", JSONArray().apply {
                put(style("all", "geometry", "color", "#000000"))
                put(style("all", "labels.icon", "visibility", "off"))
                put(style("all", "labels.text.stroke", "color", "#000000"))
                put(style("all", "labels.text.fill", "color", "#565656"))
                put(style("administrative", "labels", "visibility", "off"))
                put(style("landscape", "geometry", "color", "#000000"))
                put(style("poi", null, "visibility", "off"))
                put(style("road", "geometry", "color", "#565656"))
                put(style("road", "geometry", "visibility", "simplified"))
                put(style("road.local", "geometry", "color", "#484848"))
                put(style("road.arterial", "geometry", "color", "#6A6A6A"))
                put(style("road.highway", "geometry", "color", "#858585"))
                put(style("transit.line", "geometry", "color", "#666666"))
                put(style("transit.station", "labels.text.fill", "color", "#666666"))
                put(style("water", "geometry", "color", "#000000"))
            })
    }

    private fun style(featureType: String?, elementType: String?, key: String, value: String): JSONObject {
        return JSONObject().apply {
            featureType?.let { put("featureType", it) }
            elementType?.let { put("elementType", it) }
            put("stylers", JSONArray().put(JSONObject().put(key, value)))
        }
    }

    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(maxBytes)
        val buf = ByteArray(8192.coerceAtMost(maxBytes))
        var total = 0
        var n: Int
        while (total < maxBytes) {
            n = stream.read(buf, 0, (buf.size).coerceAtMost(maxBytes - total))
            if (n == -1) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun runClientReader(session: ClientSession) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(session.socket.inputStream, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.length > 1024) continue
                    try {
                        val parsed = ProtocolCodec.decode(line)
                        when (parsed) {
                            is ParsedMessage.TileReq -> handleTileRequest(
                                parsed.msg.z, parsed.msg.x, parsed.msg.y, parsed.msg.id, session.writer
                            )
                            else -> { /* ignore other inbound */ }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Client message parse failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Client reader ended: ${e.message}")
            } catch (t: Throwable) {
                Log.e(TAG, "Client reader error", t)
            } finally {
                try { session.socket.close() } catch (_: Exception) {}
                clientSessions.remove(session)
                clients.remove(session.writer)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return
        }

        fun acceptLoop(socket: BluetoothServerSocket, label: String) {
            Thread {
                Log.i(TAG, "$label SPP server listening on UUID $SPP_UUID")
                while (running) {
                    try {
                        val client: BluetoothSocket = socket.accept()
                        val addr = try { client.remoteDevice.address } catch (_: Exception) { "unknown" }
                        Log.i(TAG, "$label client connected: $addr")
                        val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
                        val session = ClientSession(client, writer)
                        clientSessions.add(session)
                        clients.add(writer)
                        resendCachedState(writer)
                        runClientReader(session)
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "$label accept failed: ${e.message}")
                    }
                }
            }.start()
        }

        try {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            acceptLoop(serverSocket!!, "Insecure")
        } catch (e: Exception) {
            Log.e(TAG, "Insecure server failed: ${e.message}")
        }

        try {
            val secureSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME + "_S", SPP_UUID)
            acceptLoop(secureSocket, "Secure")
        } catch (e: Exception) {
            Log.w(TAG, "Secure server failed (insecure already running): ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocationUpdate(it) }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient!!.requestLocationUpdates(request, locationCallback!!, android.os.Looper.getMainLooper())
        }
    }

    private fun onLocationUpdate(loc: Location) {
        lastLat = loc.latitude
        lastLng = loc.longitude
        val distToNext = navigationManager?.getDistanceToNextStep(loc.latitude, loc.longitude) ?: -1.0
        val speedLimit = speedLimitClient.getCachedSpeedLimit(loc.latitude, loc.longitude)
        broadcast(ProtocolCodec.encodeState(
            StateMessage(loc.latitude, loc.longitude, loc.bearing, loc.speed, loc.accuracy,
                speedLimitKmh = speedLimit, distToNextStep = distToNext)
        ))
        maybeRefreshLandmarks(loc.latitude, loc.longitude)
        navigationManager?.onLocationUpdate(loc.latitude, loc.longitude)
    }

    private fun maybeRefreshLandmarks(lat: Double, lng: Double) {
        if (cachedSettings?.showLandmarks != true) return
        if (landmarkRefreshRunning) return
        val now = System.currentTimeMillis()
        val movedEnough = if (lastLandmarkLat.isFinite() && lastLandmarkLng.isFinite()) {
            distanceMeters(lastLandmarkLat, lastLandmarkLng, lat, lng) >= LANDMARK_REFRESH_DISTANCE_M
        } else true
        if (!movedEnough && now - lastLandmarkRefreshMs < LANDMARK_REFRESH_MS) return

        landmarkRefreshRunning = true
        landmarkExecutor.execute {
            try {
                val provider = ProviderRegistry.placeSearchProvider(applicationContext)
                val seen = linkedSetOf<String>()
                val pois = mutableListOf<PoiInfo>()
                landmarkQueries.forEach { query ->
                    val results = runCatching {
                        provider.searchNearby(query.query, lat, lng, LANDMARK_RADIUS_METERS, 3)
                    }.getOrElse { error ->
                        Log.w(TAG, "POI query '${query.query}' failed: ${error.message}")
                        emptyList()
                    }
                    results.forEach { result ->
                        val name = shortPoiName(result.displayName)
                        if (name.isBlank()) return@forEach
                        val key = name.lowercase().replace(Regex("[^a-z0-9]+"), "")
                        if (!seen.add(key)) return@forEach
                        pois += PoiInfo(
                            name = name,
                            latitude = result.lat,
                            longitude = result.lng,
                            category = query.category,
                            priority = query.priority
                        )
                    }
                }
                val ranked = pois
                    .sortedWith(compareByDescending<PoiInfo> { it.priority }
                        .thenBy { distanceMeters(lat, lng, it.latitude, it.longitude) })
                    .take(MAX_LANDMARKS)
                lastLandmarkLat = lat
                lastLandmarkLng = lng
                lastLandmarkRefreshMs = System.currentTimeMillis()
                sendPois(ranked)
            } catch (e: Exception) {
                Log.w(TAG, "POI refresh failed: ${e.message}")
            } finally {
                landmarkRefreshRunning = false
            }
        }
    }

    private fun shortPoiName(displayName: String): String {
        return displayName
            .split(',')
            .firstOrNull()
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(24)
    }

    private fun distanceMeters(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, out)
        return out[0].toDouble()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, HudStreamingService::class.java).apply {
            action = ACTION_STOP_STREAMING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, HudApplication.CHANNEL_ID)
            .setContentTitle("Rokid HUD Active")
            .setContentText("Streaming to glasses")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
