package com.rokid.hud.phone

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.PowerManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.hud.shared.protocol.Waypoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_PERMISSIONS = 100
        private const val RC_WIFI_PERM = 101
        private const val RC_PICK_APK = 102
        private const val PREF_TTS = "tts_enabled"
        private const val PREF_IMPERIAL = "use_imperial"
        private const val PREF_MINI_MAP = "use_mini_map"
        private const val PREF_MINI_MAP_STYLE = "mini_map_style"
        private const val PREF_STREAM_NOTIFICATIONS = "stream_notifications"
        private const val PREF_SHOW_FULL_ROUTE_STEPS = "show_full_route_steps"
        private const val PREF_TURN_ALERT = "show_turn_alert"
        private const val PREF_TILE_CACHE_SIZE_MB = "tile_cache_size_mb"
        private const val PREF_SHOW_SPEED = "show_speed"
        private const val PREF_SHOW_SPEED_LIMIT = "show_speed_limit"
        private const val PREFS_HUD = "rokid_hud_prefs"
        private const val NEARBY_RADIUS_METERS = 2500
    }

    private lateinit var btAudioRouter: BluetoothAudioRouter

    // Header & status
    private lateinit var btnStart: Button
    private lateinit var glassesStatusDot: View
    private lateinit var glassesStatusText: TextView
    private lateinit var btnScanGlasses: Button
    private lateinit var btnUpdateGlassesApp: Button
    private lateinit var statusText: TextView

    // Navigate section
    private lateinit var searchInput: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnSearchNearby: Button
    private lateinit var btnRouteDrive: Button
    private lateinit var btnRouteWalk: Button
    private lateinit var btnRouteTransit: Button
    private lateinit var btnNearbyFood: Button
    private lateinit var btnNearbyCoffee: Button
    private lateinit var btnNearbyPharmacy: Button
    private lateinit var btnNearbyGas: Button
    private lateinit var btnNearbyMetro: Button
    private lateinit var btnShowSaved: Button
    private lateinit var searchResults: ListView
    private lateinit var routeCard: LinearLayout
    private lateinit var routeDestText: TextView
    private lateinit var routeInfoText: TextView
    private lateinit var btnPreviewRoute: Button
    private lateinit var btnNavigate: Button
    private lateinit var btnSavePlace: Button

    // Live directions + map (shown only when navigating)
    private lateinit var navStatus: LinearLayout
    private lateinit var navMapView: MapView
    private lateinit var navInstructionText: TextView
    private lateinit var navDistanceText: TextView
    private lateinit var navFullStepsPanel: LinearLayout
    private lateinit var navFullStepsList: ListView
    private lateinit var switchShowFullRouteSteps: Switch
    private lateinit var btnStopNav: Button

    // Settings
    private lateinit var switchUnits: Switch
    private lateinit var switchTts: Switch
    private lateinit var btnHudFull: Button
    private lateinit var btnHudStrip: Button
    private lateinit var btnHudSplit: Button
    private lateinit var switchMiniMap: Switch
    private lateinit var miniMapStyleGroup: RadioGroup
    private lateinit var radioStrip: RadioButton
    private lateinit var radioSplit: RadioButton
    private lateinit var btnAdvancedToggle: Button
    private lateinit var advancedSettingsPanel: LinearLayout
    private lateinit var inputGoogleApiKey: EditText
    private lateinit var switchGoogleSearch: Switch
    private lateinit var switchGoogleRoutes: Switch
    private lateinit var spinnerRouteMode: Spinner
    private lateinit var btnSaveProviders: Button
    private lateinit var providerStatusText: TextView
    private lateinit var switchWifiShare: Switch
    private lateinit var wifiShareStatus: TextView
    private lateinit var wifiInfoCard: LinearLayout
    private lateinit var wifiSsidText: TextView
    private lateinit var wifiPassText: TextView
    private lateinit var wifiClientsText: TextView
    private lateinit var hotspotSsidInput: EditText
    private lateinit var hotspotPassInput: EditText
    private lateinit var btnSendHotspotToGlasses: Button
    private lateinit var notifStatusText: TextView
    private lateinit var btnNotifAccess: Button
    private lateinit var switchStreamNotifications: Switch
    private lateinit var switchTurnAlert: Switch
    private lateinit var switchShowSpeed: Switch
    private lateinit var switchShowSpeedLimit: Switch
    private lateinit var spinnerCacheSize: Spinner
    private lateinit var btnClearCache: Button
    private lateinit var cacheSizeText: TextView

    // Managers
    private lateinit var wifiShareManager: WifiShareManager
    private lateinit var savedPlacesManager: SavedPlacesManager

    // State
    private var service: HudStreamingService? = null
    private var bound = false
    private var streaming = false
    private var searchResultsList: List<SearchResult> = emptyList()
    private var savedPlacesList: List<SavedPlace> = emptyList()
    private var selectedDest: SearchResult? = null
    private var showingSaved = false
    private var previewingRoute = false
    private var currentRouteWaypoints: List<Waypoint> = emptyList()
    private var fullRouteSteps: List<NavigationStep> = emptyList()
    private var currentTransitRoute: TransitRouteResult? = null
    private var currentTransitOptions: List<TransitRouteOption> = emptyList()
    private var selectedTransitOptionIndex: Int = 0
    private var syncingUiState = false
    private var advancedSettingsExpanded = false

    private val navMapHandler = Handler(Looper.getMainLooper())
    private val navMapUpdateRunnable = object : Runnable {
        override fun run() {
            if (!::navMapView.isInitialized || navStatus.visibility != View.VISIBLE) return
            val (lat, lng) = service?.getLastLocation() ?: return
            navMapView.controller.setCenter(GeoPoint(lat, lng))
            navMapView.controller.setZoom(17.0)
            navMapHandler.postDelayed(this, 2000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as HudStreamingService.LocalBinder).getService()
            bound = true
            streaming = true
            service?.uiCallback = navCallback
            service?.onClientConnectionChanged = clientConnectionChanged
            sendCurrentSettings()
            updateGlassesStatus()
            updateStreamingUi()
            updateCacheSizeText()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false; streaming = false
            updateGlassesStatus()
            updateStreamingUi()
        }
    }

    private val clientConnectionChanged: (Int) -> Unit = {
        updateGlassesStatus()
        updateStreamingUi()
    }

    private val navCallback = object : NavigationCallback {
        override fun onRouteCalculated(waypoints: List<Waypoint>, totalDistance: Double, totalDuration: Double, steps: List<NavigationStep>) {
            runOnUiThread {
                currentRouteWaypoints = waypoints
                fullRouteSteps = steps
                if (ProviderPrefs.getRouteMode(this@MainActivity) != RouteMode.TRANSIT) {
                    currentTransitRoute = null
                    currentTransitOptions = emptyList()
                }
                routeInfoText.text = "${formatDist(totalDistance)}  ·  ${formatTime(totalDuration)}"
                showNavStatus()
                updateNavMap()
                updateFullStepsList()
            }
        }
        override fun onStepChanged(instruction: String, maneuver: String, distance: Double) {
            runOnUiThread {
                navInstructionText.text = instruction
                navDistanceText.text = formatDist(distance)
                speakNavInstruction(instruction, distance)
            }
        }
        override fun onNavigationError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Navigation error: $message", Toast.LENGTH_LONG).show()
                btnNavigate.isEnabled = true
                routeInfoText.text = "Route failed — try again"
            }
        }
        override fun onArrived() {
            runOnUiThread {
                navInstructionText.text = "You have arrived!"
                navDistanceText.text = ""
                speakNavInstruction("You have arrived!", 0.0)
                Toast.makeText(this@MainActivity, "Arrived at destination!", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onRerouting() {
            runOnUiThread {
                navInstructionText.text = "Recalculating route..."
                navDistanceText.text = ""
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        savedPlacesManager = SavedPlacesManager(this)
        btAudioRouter = BluetoothAudioRouter(applicationContext)
        btAudioRouter.init()

        bindViews()
        setupWifiManager()
        setupListeners()
        updateGlassesStatus()
        updateNotifStatus()
    }

    private fun bindViews() {
        btnStart = findViewById(R.id.btnStart)
        glassesStatusDot = findViewById(R.id.glassesStatusDot)
        glassesStatusText = findViewById(R.id.glassesStatusText)
        btnScanGlasses = findViewById(R.id.btnScanGlasses)
        btnUpdateGlassesApp = findViewById(R.id.btnUpdateGlassesApp)
        statusText = findViewById(R.id.statusText)

        searchInput = findViewById(R.id.searchInput)
        btnSearch = findViewById(R.id.btnSearch)
        btnSearchNearby = findViewById(R.id.btnSearchNearby)
        btnRouteDrive = findViewById(R.id.btnRouteDrive)
        btnRouteWalk = findViewById(R.id.btnRouteWalk)
        btnRouteTransit = findViewById(R.id.btnRouteTransit)
        btnNearbyFood = findViewById(R.id.btnNearbyFood)
        btnNearbyCoffee = findViewById(R.id.btnNearbyCoffee)
        btnNearbyPharmacy = findViewById(R.id.btnNearbyPharmacy)
        btnNearbyGas = findViewById(R.id.btnNearbyGas)
        btnNearbyMetro = findViewById(R.id.btnNearbyMetro)
        btnShowSaved = findViewById(R.id.btnShowSaved)
        searchResults = findViewById(R.id.searchResults)
        routeCard = findViewById(R.id.routeCard)
        routeDestText = findViewById(R.id.routeDestText)
        routeInfoText = findViewById(R.id.routeInfoText)
        btnPreviewRoute = findViewById(R.id.btnPreviewRoute)
        btnNavigate = findViewById(R.id.btnNavigate)
        btnSavePlace = findViewById(R.id.btnSavePlace)

        navStatus = findViewById(R.id.navStatus)
        navMapView = findViewById(R.id.navMapView)
        navInstructionText = findViewById(R.id.navInstructionText)
        navDistanceText = findViewById(R.id.navDistanceText)
        navFullStepsPanel = findViewById(R.id.navFullStepsPanel)
        navFullStepsList = findViewById(R.id.navFullStepsList)
        switchShowFullRouteSteps = findViewById(R.id.switchShowFullRouteSteps)
        btnStopNav = findViewById(R.id.btnStopNav)
        initNavMap()

        switchUnits = findViewById(R.id.switchUnits)
        switchTts = findViewById(R.id.switchTts)
        btnHudFull = findViewById(R.id.btnHudFull)
        btnHudStrip = findViewById(R.id.btnHudStrip)
        btnHudSplit = findViewById(R.id.btnHudSplit)
        switchMiniMap = findViewById(R.id.switchMiniMap)
        miniMapStyleGroup = findViewById(R.id.miniMapStyleGroup)
        radioStrip = findViewById(R.id.radioStrip)
        radioSplit = findViewById(R.id.radioSplit)
        btnAdvancedToggle = findViewById(R.id.btnAdvancedToggle)
        advancedSettingsPanel = findViewById(R.id.advancedSettingsPanel)
        inputGoogleApiKey = findViewById(R.id.inputGoogleApiKey)
        switchGoogleSearch = findViewById(R.id.switchGoogleSearch)
        switchGoogleRoutes = findViewById(R.id.switchGoogleRoutes)
        spinnerRouteMode = findViewById(R.id.spinnerRouteMode)
        btnSaveProviders = findViewById(R.id.btnSaveProviders)
        providerStatusText = findViewById(R.id.providerStatusText)
        switchWifiShare = findViewById(R.id.switchWifiShare)
        wifiShareStatus = findViewById(R.id.wifiShareStatus)
        wifiInfoCard = findViewById(R.id.wifiInfoCard)
        wifiSsidText = findViewById(R.id.wifiSsidText)
        wifiPassText = findViewById(R.id.wifiPassText)
        wifiClientsText = findViewById(R.id.wifiClientsText)
        hotspotSsidInput = findViewById(R.id.hotspotSsidInput)
        hotspotPassInput = findViewById(R.id.hotspotPassInput)
        btnSendHotspotToGlasses = findViewById(R.id.btnSendHotspotToGlasses)
        notifStatusText = findViewById(R.id.notifStatusText)
        btnNotifAccess = findViewById(R.id.btnNotifAccess)
        switchStreamNotifications = findViewById(R.id.switchStreamNotifications)

        switchTts.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_TTS, false)
        switchUnits.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)
        switchMiniMap.isChecked = getPreferences(MODE_PRIVATE).getBoolean(PREF_MINI_MAP, false)
        val savedStyle = getPreferences(MODE_PRIVATE).getString(PREF_MINI_MAP_STYLE, "strip")
        if (savedStyle == "split") radioSplit.isChecked = true else radioStrip.isChecked = true
        miniMapStyleGroup.visibility = View.GONE
        advancedSettingsPanel.visibility = View.GONE
        inputGoogleApiKey.setText(ProviderPrefs.getGoogleApiKey(this))
        switchGoogleSearch.isChecked = ProviderPrefs.useGoogleSearch(this)
        switchGoogleRoutes.isChecked = ProviderPrefs.useGoogleRoutes(this)
        switchStreamNotifications.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_STREAM_NOTIFICATIONS, true)

        switchTurnAlert = findViewById(R.id.switchTurnAlert)
        switchTurnAlert.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_TURN_ALERT, false)

        switchShowSpeed = findViewById(R.id.switchShowSpeed)
        switchShowSpeed.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_SPEED, true)

        switchShowSpeedLimit = findViewById(R.id.switchShowSpeedLimit)
        switchShowSpeedLimit.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_SPEED_LIMIT, true)

        spinnerCacheSize = findViewById(R.id.spinnerCacheSize)
        btnClearCache = findViewById(R.id.btnClearCache)
        cacheSizeText = findViewById(R.id.cacheSizeText)
        setupRouteModeSpinner()
        updateRouteModeQuickButtons()
        updateHudLayoutButtons()
        updateAdvancedToggle()
        updateProviderStatus()
        setupCacheSpinner()
    }

    private fun setupWifiManager() {
        wifiShareManager = WifiShareManager(applicationContext)
        wifiShareManager.init()
        wifiShareManager.onStateChanged = { state -> runOnUiThread { updateWifiUi(state) } }
        switchWifiShare.isChecked = wifiShareManager.wasEnabled()
        if (wifiShareManager.wasEnabled()) {
            wifiShareManager.startSharing()
        }
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            if (streaming) stopStreaming() else checkPermissionsAndStart()
        }
        btnScanGlasses.setOnClickListener {
            startActivity(Intent(this, DeviceScanActivity::class.java))
        }
        btnUpdateGlassesApp.setOnClickListener { openApkPicker() }

        btnSearch.setOnClickListener { performSearch() }
        searchInput.setOnEditorActionListener { _, _, _ -> performSearch(); true }
        btnSearchNearby.setOnClickListener { performNearbySearch() }
        btnRouteDrive.setOnClickListener { setRouteModeQuick(RouteMode.DRIVE) }
        btnRouteWalk.setOnClickListener { setRouteModeQuick(RouteMode.WALK) }
        btnRouteTransit.setOnClickListener { setRouteModeQuick(RouteMode.TRANSIT) }
        btnNearbyFood.setOnClickListener { performNearbyCategorySearch("restaurant", "Food") }
        btnNearbyCoffee.setOnClickListener { performNearbyCategorySearch("cafe", "Coffee") }
        btnNearbyPharmacy.setOnClickListener { performNearbyCategorySearch("pharmacy", "Pharmacy") }
        btnNearbyGas.setOnClickListener { performNearbyCategorySearch("gas station", "Gas") }
        btnNearbyMetro.setOnClickListener { performNearbyCategorySearch("subway station", "Metro") }
        btnShowSaved.setOnClickListener { toggleSavedPlaces() }
        searchResults.setOnItemClickListener { _, _, pos, _ -> onItemSelected(pos) }
        btnPreviewRoute.setOnClickListener { previewRoute() }
        btnNavigate.setOnClickListener { startNavigation() }
        btnSavePlace.setOnClickListener { saveCurrentPlace() }
        btnStopNav.setOnClickListener { stopNavigation() }
        btnHudFull.setOnClickListener { setHudLayoutQuick(useMiniMap = false, miniMapStyle = "strip") }
        btnHudStrip.setOnClickListener { setHudLayoutQuick(useMiniMap = true, miniMapStyle = "strip") }
        btnHudSplit.setOnClickListener { setHudLayoutQuick(useMiniMap = true, miniMapStyle = "split") }
        btnAdvancedToggle.setOnClickListener { toggleAdvancedSettings() }
        // Let the steps list scroll inside the outer ScrollView
        navFullStepsList.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        switchShowFullRouteSteps.isChecked = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false)
        switchShowFullRouteSteps.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_FULL_ROUTE_STEPS, isChecked).apply()
            navFullStepsPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) updateFullStepsList()
            sendCurrentSettings()
        }
        btnNotifAccess.setOnClickListener { openNotificationListenerSettings() }

        switchTts.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_TTS, isChecked).apply()
            sendCurrentSettings()
        }

        switchUnits.setOnCheckedChangeListener { _, isChecked ->
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_IMPERIAL, isChecked).apply()
            sendCurrentSettings()
        }

        switchMiniMap.setOnCheckedChangeListener { _, isChecked ->
            if (syncingUiState) return@setOnCheckedChangeListener
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_MINI_MAP, isChecked).apply()
            miniMapStyleGroup.visibility = View.GONE
            updateHudLayoutButtons()
            sendCurrentSettings()
        }

        miniMapStyleGroup.setOnCheckedChangeListener { _, checkedId ->
            if (syncingUiState) return@setOnCheckedChangeListener
            val style = if (checkedId == R.id.radioSplit) "split" else "strip"
            getPreferences(MODE_PRIVATE).edit().putString(PREF_MINI_MAP_STYLE, style).apply()
            updateHudLayoutButtons()
            sendCurrentSettings()
        }

        switchGoogleSearch.setOnCheckedChangeListener { _, isChecked ->
            ProviderPrefs.setUseGoogleSearch(this, isChecked)
            updateProviderStatus()
        }

        switchGoogleRoutes.setOnCheckedChangeListener { _, isChecked ->
            ProviderPrefs.setUseGoogleRoutes(this, isChecked)
            updateProviderStatus()
        }

        btnSaveProviders.setOnClickListener {
            ProviderPrefs.setGoogleApiKey(this, inputGoogleApiKey.text.toString())
            updateProviderStatus()
            Toast.makeText(this, "Provider settings saved", Toast.LENGTH_SHORT).show()
        }

        switchStreamNotifications.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_STREAM_NOTIFICATIONS, isChecked).apply()
            sendCurrentSettings()
        }

        switchTurnAlert.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_TURN_ALERT, isChecked).apply()
            sendCurrentSettings()
        }

        switchShowSpeed.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_SPEED, isChecked).apply()
            sendCurrentSettings()
        }

        switchShowSpeedLimit.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putBoolean(PREF_SHOW_SPEED_LIMIT, isChecked).apply()
            sendCurrentSettings()
        }

        switchWifiShare.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkWifiPermissionsAndStart() else wifiShareManager.stopSharing()
        }

        btnSendHotspotToGlasses.setOnClickListener { sendHotspotToGlasses() }

        findViewById<Button>(R.id.btnBuyMeACoffee).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Support Rokid Maps")
                .setMessage("If you enjoy using Rokid Maps, consider buying me a coffee! Your support helps keep development going.")
                .setPositiveButton("Open Link") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/charleshartmann")))
                }
                .setNegativeButton("Maybe Later", null)
                .show()
        }
    }

    private var apkProgressDialog: AlertDialog? = null

    private fun openApkPicker() {
        if (!bound || service?.hasConnectedClient() != true) {
            Toast.makeText(this, "Start streaming and connect glasses first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/apk"))
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select glasses APK"), RC_PICK_APK)
        } catch (e: Exception) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), RC_PICK_APK)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PICK_APK && resultCode == RESULT_OK && data?.data != null) {
            sendApkToGlasses(data.data!!)
        }
    }

    private fun sendApkToGlasses(uri: Uri) {
        apkProgressDialog = AlertDialog.Builder(this)
            .setTitle("Update glasses app")
            .setMessage("Sending APK... 0%")
            .setCancelable(false)
            .show()
        service!!.sendApkToGlasses(
            uri,
            onProgress = { sent, total ->
                val pct = if (total > 0) (100 * sent / total) else 0
                apkProgressDialog?.setMessage("Sending APK... $pct%")
            },
            onDone = {
                apkProgressDialog?.dismiss()
                apkProgressDialog = null
                Toast.makeText(this, "APK sent. Open the glasses and confirm install when prompted.", Toast.LENGTH_LONG).show()
            },
            onError = { msg ->
                apkProgressDialog?.dismiss()
                apkProgressDialog = null
                Toast.makeText(this, "Failed: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun sendHotspotToGlasses() {
        val ssid = hotspotSsidInput.text.toString().trim()
        val pass = hotspotPassInput.text.toString()
        if (ssid.isBlank()) {
            Toast.makeText(this, "Enter your hotspot name (SSID)", Toast.LENGTH_SHORT).show()
            return
        }
        if (!bound || service?.hasConnectedClient() != true) {
            Toast.makeText(this, "Start streaming first so glasses are connected", Toast.LENGTH_SHORT).show()
            return
        }
        service!!.sendWifiCreds(ssid, pass, true)
        Toast.makeText(this, "Sent to glasses — they will enable Wi‑Fi and connect for internet", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        navMapView.onResume()
        updateGlassesStatus()
        updateNotifStatus()
        if (bound) {
            service?.uiCallback = navCallback
            service?.onClientConnectionChanged = clientConnectionChanged
        }
        updateStreamingUi()
        if (streaming) btAudioRouter.connectAudio()
        if (bound) updateCacheSizeText()
    }

    override fun onPause() {
        super.onPause()
        navMapView.onPause()
    }

    override fun onDestroy() {
        btAudioRouter.release()
        wifiShareManager.release()
        if (bound) {
            service?.uiCallback = null
            service?.onClientConnectionChanged = null
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    // ── Glasses status ─────────────────────────────────────────────────────

    private fun updateGlassesStatus() {
        val savedName = GlassesPrefs.getName(this)
        val connected = service?.hasConnectedClient() == true
        if (connected && !savedName.isNullOrBlank()) {
            glassesStatusText.text = "Connected: $savedName"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
            btnScanGlasses.text = "Change"
        } else if (connected) {
            glassesStatusText.text = "Glasses connected"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_connected)
            btnScanGlasses.text = "Change"
        } else if (!savedName.isNullOrBlank()) {
            glassesStatusText.text = "Selected: $savedName"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
            btnScanGlasses.text = "Change"
        } else {
            glassesStatusText.text = "No glasses selected"
            glassesStatusDot.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
            btnScanGlasses.text = "Pair Glasses"
        }
    }

    private fun updateStreamingUi() {
        val connectedClients = service?.connectedClientCount() ?: 0
        val selectedName = GlassesPrefs.getName(this)?.takeIf { it.isNotBlank() } ?: "glasses"
        if (streaming) {
            btnStart.text = "Stop Streaming"
            btnStart.isEnabled = true
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
            statusText.text = if (connectedClients > 0) {
                "Connected to $selectedName - search a destination"
            } else {
                "Streaming active - waiting for glasses"
            }
        } else {
            btnStart.text = "Start Streaming"
            btnStart.isEnabled = true
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00E676.toInt())
            statusText.text = "Tap Start Streaming to begin"
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) return
        if (isNearbyIntent(query)) {
            performNearbySearch(cleanNearbyQuery(query))
            return
        }
        hideKeyboard()
        showingSaved = false
        btnSearch.isEnabled = false
        statusText.text = "Searching with ${ProviderPrefs.currentSearchProviderLabel(this)}..."

        Thread {
            try {
                val results = ProviderRegistry.placeSearchProvider(this).search(query)
                runOnUiThread {
                    renderSearchResults(results, nearby = false)
                    btnSearch.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Search error: ${e.message}"
                    Toast.makeText(
                        this,
                        e.message ?: "Search failed",
                        Toast.LENGTH_LONG
                    ).show()
                    btnSearch.isEnabled = true
                }
            }
        }.start()
    }

    private fun performNearbySearch(explicitQuery: String? = null) {
        val query = explicitQuery?.trim()?.takeIf { it.isNotBlank() }
            ?: searchInput.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Enter what you want to find nearby", Toast.LENGTH_SHORT).show()
            return
        }
        val origin = getNearbyOriginOrShowError() ?: return
        hideKeyboard()
        showingSaved = false
        btnSearch.isEnabled = false
        btnSearchNearby.isEnabled = false
        statusText.text = "Searching nearby with ${ProviderPrefs.currentSearchProviderLabel(this)}..."

        Thread {
            try {
                val results = ProviderRegistry.placeSearchProvider(this).searchNearby(
                    query = query,
                    lat = origin.first,
                    lng = origin.second,
                    radiusMeters = NEARBY_RADIUS_METERS
                )
                runOnUiThread {
                    renderSearchResults(results, nearby = true)
                    btnSearch.isEnabled = true
                    btnSearchNearby.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Nearby search error: ${e.message}"
                    Toast.makeText(this, e.message ?: "Nearby search failed", Toast.LENGTH_LONG).show()
                    btnSearch.isEnabled = true
                    btnSearchNearby.isEnabled = true
                }
            }
        }.start()
    }

    private fun performNearbyCategorySearch(query: String, label: String) {
        searchInput.setText(label)
        performNearbySearch(query)
    }

    private fun renderSearchResults(results: List<SearchResult>, nearby: Boolean) {
        searchResultsList = results
        if (results.isEmpty()) {
            statusText.text = if (nearby) "No nearby results found" else "No results found"
            searchResults.visibility = View.GONE
        } else {
            setResultsList(results.map { it.displayName }, false)
            searchResults.visibility = View.VISIBLE
            adjustListHeight()
            statusText.text = if (nearby) {
                "${results.size} nearby result(s) via ${ProviderPrefs.currentSearchProviderLabel(this)}"
            } else {
                "${results.size} results via ${ProviderPrefs.currentSearchProviderLabel(this)}"
            }
        }
    }

    private fun getNearbyOriginOrShowError(): Pair<Double, Double>? {
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first to get your location", Toast.LENGTH_SHORT).show()
            statusText.text = "Nearby search needs current location"
            return null
        }
        val origin = service!!.getLastLocation()
        if (origin.first == 0.0 && origin.second == 0.0) {
            Toast.makeText(this, "Waiting for GPS location", Toast.LENGTH_SHORT).show()
            statusText.text = "Waiting for current location..."
            return null
        }
        return origin
    }

    private fun isNearbyIntent(query: String): Boolean {
        val normalized = query.lowercase()
        return listOf(
            "near me",
            "nearest",
            "closest",
            "around me",
            "plus proche",
            "près de moi",
            "pres de moi",
            "autour de moi"
        ).any { normalized.contains(it) }
    }

    private fun cleanNearbyQuery(query: String): String {
        var cleaned = query
        val patterns = listOf(
            "(?i)\\b(find|show|search|look for|trouve|cherche|montre)\\b",
            "(?i)\\b(the\\s+)?(nearest|closest)\\b",
            "(?i)\\bnear me\\b",
            "(?i)\\baround me\\b",
            "(?i)\\b(le\\s+|la\\s+|les\\s+)?plus proche(s)?\\b",
            "(?i)\\b(pr[eè]s|autour) de moi\\b"
        )
        patterns.forEach { pattern ->
            cleaned = cleaned.replace(Regex(pattern), " ")
        }
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim(' ', ',', '-', ':')
        return cleaned.ifBlank { query.trim() }
    }

    // ── Saved places ───────────────────────────────────────────────────────

    private fun toggleSavedPlaces() {
        if (showingSaved && searchResults.visibility == View.VISIBLE) {
            searchResults.visibility = View.GONE
            showingSaved = false
            btnShowSaved.text = "Saved Places"
            return
        }
        showingSaved = true
        savedPlacesList = savedPlacesManager.getAll()
        if (savedPlacesList.isEmpty()) {
            Toast.makeText(this, "No saved places yet", Toast.LENGTH_SHORT).show()
            searchResults.visibility = View.GONE
            return
        }
        setResultsList(savedPlacesList.map { it.name }, true)
        searchResults.visibility = View.VISIBLE
        adjustListHeight()
        btnShowSaved.text = "Hide Saved"
        statusText.text = "${savedPlacesList.size} saved place(s)"

        searchResults.setOnItemLongClickListener { _, _, pos, _ ->
            if (showingSaved && pos < savedPlacesList.size) {
                val place = savedPlacesList[pos]
                savedPlacesManager.delete(place)
                Toast.makeText(this, "Removed: ${place.name}", Toast.LENGTH_SHORT).show()
                toggleSavedPlaces()
                true
            } else false
        }
    }

    private fun saveCurrentPlace() {
        val dest = selectedDest ?: return
        val parts = dest.displayName.split(",").map { it.trim() }
        val shortName = if (parts.size >= 2) parts.take(3).joinToString(", ") else dest.displayName
        savedPlacesManager.save(SavedPlace(shortName, dest.lat, dest.lng))
        Toast.makeText(this, "Saved: $shortName", Toast.LENGTH_SHORT).show()
        btnSavePlace.text = "Saved!"
        btnSavePlace.isEnabled = false
    }

    private fun onItemSelected(position: Int) {
        if (showingSaved) {
            if (position >= savedPlacesList.size) return
            val place = savedPlacesList[position]
            selectedDest = SearchResult(place.name, place.lat, place.lng)
        } else {
            if (position >= searchResultsList.size) return
            selectedDest = searchResultsList[position]
        }
        searchResults.visibility = View.GONE

        val dest = selectedDest!!
        routeDestText.text = dest.displayName
        routeInfoText.text = "Preview route or start ${ProviderPrefs.currentRouteModeLabel(this).lowercase()} navigation"
        routeCard.visibility = View.VISIBLE
        navStatus.visibility = View.GONE
        previewingRoute = false
        btnPreviewRoute.isEnabled = true
        btnNavigate.isEnabled = true
        btnSavePlace.text = "Save"
        btnSavePlace.isEnabled = true
    }

    private fun setResultsList(items: List<String>, isSaved: Boolean) {
        searchResults.adapter = object : ArrayAdapter<String>(this, R.layout.item_search_result, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_search_result, parent, false)
                val icon = view.findViewById<TextView>(R.id.resultIcon)
                val text = view.findViewById<TextView>(android.R.id.text1)
                icon.text = if (isSaved) "\u2B50" else "\uD83D\uDCCD"
                text.text = getItem(position)
                return view
            }
        }
    }

    private fun adjustListHeight() {
        val count = searchResults.adapter?.count ?: 0
        val maxItems = minOf(count, 5)
        val itemH = (52 * resources.displayMetrics.density).toInt()
        val params = searchResults.layoutParams
        params.height = maxItems * itemH
        searchResults.layoutParams = params
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun startNavigation() {
        val dest = selectedDest ?: return
        if (!bound || service == null) {
            Toast.makeText(this, "Start streaming first", Toast.LENGTH_SHORT).show()
            return
        }
        previewingRoute = false
        routeInfoText.text = "Calculating ${ProviderPrefs.currentRouteModeLabel(this).lowercase()} route..."
        btnNavigate.isEnabled = false
        service?.sendNavMode(previewActive = false)
        val initialRoute = if (ProviderPrefs.getRouteMode(this) == RouteMode.TRANSIT) {
            currentTransitRoute?.route
        } else null
        service!!.startNavigation(dest.lat, dest.lng, initialRoute)
    }

    private fun previewRoute() {
        val dest = selectedDest ?: return
        val origin = getNearbyOriginOrShowError() ?: return
        val routeMode = ProviderPrefs.getRouteMode(this)
        btnPreviewRoute.isEnabled = false
        btnNavigate.isEnabled = false
        routeInfoText.text = "Calculating route preview..."
        Thread {
            try {
                val transitOptions = if (routeMode == RouteMode.TRANSIT) {
                    ProviderRegistry.transitRouteProvider(this).getTransitRouteOptions(
                        origin.first,
                        origin.second,
                        dest.lat,
                        dest.lng
                    )
                } else null
                val transitResult = transitOptions?.firstOrNull()?.result
                val result = transitResult?.route ?: ProviderRegistry.routeProvider(this).getRoute(
                    origin.first,
                    origin.second,
                    dest.lat,
                    dest.lng,
                    routeMode
                )
                runOnUiThread {
                    if (routeMode == RouteMode.TRANSIT && !transitOptions.isNullOrEmpty()) {
                        currentTransitOptions = transitOptions
                        showTransitRoutePicker(transitOptions)
                    } else {
                        currentTransitOptions = emptyList()
                        currentTransitRoute = null
                        applyPreviewRoute(result, null)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    previewingRoute = false
                    currentTransitRoute = null
                    routeInfoText.text = "Preview failed — try again"
                    Toast.makeText(this, "Preview error: ${e.message}", Toast.LENGTH_LONG).show()
                    btnPreviewRoute.isEnabled = true
                    btnNavigate.isEnabled = true
                }
            }
        }.start()
    }

    private fun showNavStatus(isPreview: Boolean = false) {
        navStatus.visibility = View.VISIBLE
        btnNavigate.isEnabled = true
        val showFullSteps = if (isPreview) true else getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false)
        switchShowFullRouteSteps.isChecked = showFullSteps
        navFullStepsPanel.visibility = if (showFullSteps) View.VISIBLE else View.GONE
        if (showFullSteps) updateFullStepsList()
        navMapHandler.removeCallbacks(navMapUpdateRunnable)
        if (!isPreview) {
            navMapHandler.postDelayed(navMapUpdateRunnable, 500L)
        }
    }

    private fun stopNavigation() {
        navMapHandler.removeCallbacks(navMapUpdateRunnable)
        service?.stopNavigation()
        previewingRoute = false
        navStatus.visibility = View.GONE
        navInstructionText.text = ""
        navDistanceText.text = ""
        currentRouteWaypoints = emptyList()
        fullRouteSteps = emptyList()
        currentTransitRoute = null
        currentTransitOptions = emptyList()
        selectedTransitOptionIndex = 0
        TransitSelectionSession.clear()
    }

    private fun updateFullStepsList() {
        val items = if (currentTransitRoute != null && ProviderPrefs.getRouteMode(this) == RouteMode.TRANSIT) {
            currentTransitRoute!!.legs.mapIndexed { i, leg ->
                formatTransitLegForList(i, leg)
            }
        } else {
            fullRouteSteps.mapIndexed { i, step ->
                "${i + 1}. ${step.instruction} — ${formatDist(step.distance)}"
            }
        }
        navFullStepsList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun applyPreviewRoute(result: RouteResult, transitResult: TransitRouteResult?) {
        currentRouteWaypoints = result.waypoints
        fullRouteSteps = result.steps
        currentTransitRoute = transitResult
        if (transitResult == null) {
            currentTransitOptions = emptyList()
            selectedTransitOptionIndex = 0
            TransitSelectionSession.clear()
        }
        previewingRoute = true
        routeInfoText.text = buildRouteInfoText(result, transitResult, preview = true)
        navInstructionText.text = "Preview ${ProviderPrefs.currentRouteModeLabel(this).lowercase()} route"
        navDistanceText.text = buildNavDistanceText(result, transitResult)
        showNavStatus(isPreview = true)
        updateNavMap()
        updateFullStepsList()
        service?.showRoutePreview(result)
        btnPreviewRoute.isEnabled = true
        btnNavigate.isEnabled = true
    }

    private fun showTransitRoutePicker(options: List<TransitRouteOption>) {
        if (options.isEmpty()) {
            routeInfoText.text = "No transit options found"
            btnPreviewRoute.isEnabled = true
            btnNavigate.isEnabled = true
            return
        }
        val initialIndex = TransitSelectionSession.selectedOptionIndex.coerceIn(0, options.lastIndex)
        var selectedIndex = initialIndex
        val adapter = TransitOptionAdapter(options, initialIndex)
        val listView = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = 10
            choiceMode = ListView.CHOICE_MODE_SINGLE
            isVerticalScrollBarEnabled = false
            setOnItemClickListener { _, _, which, _ ->
                selectedIndex = which
                adapter.setSelectedIndex(which)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Choose Transit Route")
            .setView(listView)
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                btnPreviewRoute.isEnabled = true
                btnNavigate.isEnabled = true
                routeInfoText.text = "Transit preview cancelled"
            }
            .setPositiveButton("Use Route") { _, _ ->
                selectedTransitOptionIndex = selectedIndex
                TransitSelectionSession.selectedOptionIndex = selectedIndex
                val chosen = options[selectedIndex]
                applyPreviewRoute(chosen.result.route, chosen.result)
            }
            .setCancelable(true)
            .show()
    }

    private fun buildTransitOptionLabel(index: Int, option: TransitRouteOption): String {
        val result = option.result
        val lines = result.legs.filter { it.type == TransitLegType.TRANSIT }
            .mapNotNull { it.lineName }
            .distinct()
            .take(3)
        val lineSummary = if (lines.isNotEmpty()) lines.joinToString(" -> ") else "Transit"
        val suffix = when {
            option.routeLabels.any { it.equals("DEFAULT_ROUTE", ignoreCase = true) } -> "default"
            else -> "alt ${index + 1}"
        }
        return "${index + 1}. ${formatTime(result.totalDurationSeconds)} · ${result.transferCount} transfer(s) · $lineSummary · $suffix"
    }

    private fun buildTransitOptionLabelRich(index: Int, option: TransitRouteOption): String {
        val result = option.result
        val lines = result.legs.filter { it.type == TransitLegType.TRANSIT }
            .mapNotNull { it.lineName }
            .distinct()
            .take(3)
        val walkDistance = result.legs
            .filter { it.type == TransitLegType.WALK }
            .sumOf { it.distanceMeters }
        val suffix = if (option.routeLabels.any { it.equals("DEFAULT_ROUTE", ignoreCase = true) }) {
            "default"
        } else {
            "alt ${index + 1}"
        }
        val lineSummary = if (lines.isNotEmpty()) {
            lines.joinToString("   ") { formatTransitLineBadge(it) }
        } else {
            "🚉 Transit"
        }
        return buildString {
            append("${index + 1}. ${formatTime(result.totalDurationSeconds)}  ·  ${result.transferCount} transfer(s)  ·  $suffix")
            append('\n')
            append(lineSummary)
            append('\n')
            append("🚶 ${formatDist(walkDistance)}")
        }
    }

    private fun formatTransitLineBadge(lineName: String): String {
        val upper = lineName.uppercase()
        return when {
            upper.startsWith("M") || upper.contains("METRO") -> "Ⓜ️ $lineName"
            upper.contains("RER") || upper.startsWith("R") -> "🚆 $lineName"
            upper.contains("BUS") -> "🚌 $lineName"
            else -> "🚉 $lineName"
        }
    }

    private fun buildTransitOptionTitle(index: Int, option: TransitRouteOption): String {
        return "Option ${index + 1}  ·  ${formatTime(option.result.totalDurationSeconds)}"
    }

    private fun buildTransitOptionTag(index: Int, option: TransitRouteOption): String {
        return if (option.routeLabels.any { it.equals("DEFAULT_ROUTE", ignoreCase = true) }) {
            "BEST"
        } else {
            "ALT ${index + 1}"
        }
    }

    private fun buildTransitOptionMeta(option: TransitRouteOption): String {
        val walkDistance = option.result.legs
            .filter { it.type == TransitLegType.WALK }
            .sumOf { it.distanceMeters }
        val changesText = if (option.result.transferCount == 0) {
            "Direct"
        } else {
            "${option.result.transferCount} change${if (option.result.transferCount > 1) "s" else ""}"
        }
        return "$changesText  ·  ${formatDist(walkDistance)} walk"
    }

    private fun buildTransitOptionLines(option: TransitRouteOption): String {
        val lines = option.result.legs
            .filter { it.type == TransitLegType.TRANSIT }
            .mapNotNull { it.lineName }
            .distinct()
            .take(4)
        return if (lines.isNotEmpty()) {
            lines.joinToString("  ->  ") { formatTransitLineBadge(it) }
        } else {
            "Transit"
        }
    }

    private fun buildTransitOptionStops(option: TransitRouteOption): String {
        val transitLegs = option.result.legs.filter { it.type == TransitLegType.TRANSIT }
        if (transitLegs.isEmpty()) {
            return "Walking-only transit fallback"
        }
        val firstLeg = transitLegs.first()
        val lastLeg = transitLegs.last()
        val start = firstLeg.departureStop?.name ?: "Board"
        val end = lastLeg.arrivalStop?.name ?: "Arrive"
        val headsign = firstLeg.headsign?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""
        return "$start  ->  $end$headsign"
    }

    private inner class TransitOptionAdapter(
        private val items: List<TransitRouteOption>,
        private var selectedIndex: Int
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): TransitRouteOption = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        fun setSelectedIndex(index: Int) {
            selectedIndex = index
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_transit_option, parent, false)
            val option = getItem(position)
            val isSelected = position == selectedIndex

            val root = view.findViewById<LinearLayout>(R.id.transitOptionRoot)
            val title = view.findViewById<TextView>(R.id.transitOptionTitle)
            val tag = view.findViewById<TextView>(R.id.transitOptionTag)
            val meta = view.findViewById<TextView>(R.id.transitOptionMeta)
            val lines = view.findViewById<TextView>(R.id.transitOptionLines)
            val stops = view.findViewById<TextView>(R.id.transitOptionStops)

            root.setBackgroundColor(Color.parseColor(if (isSelected) "#1E3A2F" else "#181818"))
            title.text = buildTransitOptionTitle(position, option)
            tag.text = buildTransitOptionTag(position, option)
            meta.text = buildTransitOptionMeta(option)
            lines.text = buildTransitOptionLines(option)
            stops.text = buildTransitOptionStops(option)

            title.setTextColor(Color.parseColor("#FFFFFF"))
            tag.setTextColor(Color.parseColor(if (isSelected) "#C8FFD7" else "#00E676"))
            meta.setTextColor(Color.parseColor(if (isSelected) "#D8F5DE" else "#BDBDBD"))
            lines.setTextColor(Color.parseColor("#FFFFFF"))
            stops.setTextColor(Color.parseColor(if (isSelected) "#C8FFD7" else "#81C784"))
            return view
        }
    }

    private fun buildRouteInfoText(
        result: RouteResult,
        transitResult: TransitRouteResult?,
        preview: Boolean
    ): String {
        val parts = mutableListOf(
            formatDist(result.totalDistance),
            formatTime(result.totalDuration)
        )
        if (transitResult != null) {
            val lineCount = transitResult.legs.count { it.type == TransitLegType.TRANSIT }
            if (lineCount > 0) {
                parts += "$lineCount line(s)"
            }
            if (transitResult.transferCount > 0) {
                parts += "${transitResult.transferCount} transfer(s)"
            }
        }
        if (preview) {
            parts += "preview ready"
        }
        return parts.joinToString("  ·  ")
    }

    private fun buildNavDistanceText(
        result: RouteResult,
        transitResult: TransitRouteResult?
    ): String {
        val parts = mutableListOf(
            formatDist(result.totalDistance),
            formatTime(result.totalDuration)
        )
        if (transitResult != null && transitResult.transferCount > 0) {
            parts += "${transitResult.transferCount} transfer(s)"
        }
        return parts.joinToString("  ·  ")
    }

    private fun formatTransitLegForList(index: Int, leg: TransitLeg): String {
        return when (leg.type) {
            TransitLegType.WALK -> {
                "${index + 1}. Walk ${formatDist(leg.distanceMeters)} · ${formatTime(leg.durationSeconds)} · ${leg.instruction}"
            }
            TransitLegType.TRANSIT -> {
                val line = leg.lineName ?: "Transit"
                val headsign = leg.headsign?.let { " toward $it" }.orEmpty()
                val departure = leg.departureStop?.name?.let { " from $it" }.orEmpty()
                val arrival = leg.arrivalStop?.name?.let { " to $it" }.orEmpty()
                val stops = leg.stopCount?.takeIf { it > 0 }?.let { " · $it stops" }.orEmpty()
                "${index + 1}. Take $line$headsign$departure$arrival$stops · ${formatTime(leg.durationSeconds)}"
            }
        }
    }

    private fun initNavMap() {
        navMapView.setTileSource(TileSourceFactory.MAPNIK)
        navMapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        navMapView.setMultiTouchControls(true)
        navMapView.controller.setZoom(15.0)
    }

    private fun updateNavMap() {
        if (currentRouteWaypoints.isEmpty()) return
        navMapView.overlays.removeIf { it is Polyline }
        val line = Polyline().apply {
            outlinePaint.color = Color.parseColor("#00E676")
            outlinePaint.strokeWidth = 12f
            outlinePaint.isAntiAlias = true
            setPoints(currentRouteWaypoints.map { GeoPoint(it.latitude, it.longitude) })
        }
        navMapView.overlays.add(line)
        val box = BoundingBox.fromGeoPoints(currentRouteWaypoints.map { GeoPoint(it.latitude, it.longitude) })
        navMapView.zoomToBoundingBox(box, false)
        if (!previewingRoute) {
            val (lat, lng) = service?.getLastLocation() ?: run {
                val first = currentRouteWaypoints.first()
                Pair(first.latitude, first.longitude)
            }
            navMapView.controller.setCenter(GeoPoint(lat, lng))
        }
        navMapView.invalidate()
    }

    // ── Wi-Fi sharing ──────────────────────────────────────────────────────

    private fun updateWifiUi(state: WifiShareManager.State) {
        when (state) {
            WifiShareManager.State.OFF -> {
                wifiShareStatus.text = "Create Wi-Fi Direct hotspot for glasses"
                wifiInfoCard.visibility = View.GONE
                switchWifiShare.isChecked = false
                service?.sendWifiCreds("", "", false)
            }
            WifiShareManager.State.CREATING -> {
                wifiShareStatus.text = "Creating hotspot..."
                wifiInfoCard.visibility = View.GONE
            }
            WifiShareManager.State.ACTIVE -> {
                wifiShareStatus.text = "Hotspot active"
                wifiInfoCard.visibility = View.VISIBLE
                wifiSsidText.text = wifiShareManager.groupSsid
                wifiPassText.text = wifiShareManager.groupPassphrase
                val n = wifiShareManager.connectedClients
                wifiClientsText.text = if (n == 0) "Sending credentials to glasses..." else "$n device(s) connected"
                switchWifiShare.isChecked = true
                service?.sendWifiCreds(wifiShareManager.groupSsid, wifiShareManager.groupPassphrase, true)
            }
            WifiShareManager.State.FAILED -> {
                wifiShareStatus.text = "Failed: ${wifiShareManager.lastError}"
                wifiInfoCard.visibility = View.GONE
            }
        }
    }

    private fun checkWifiPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_WIFI_PERM)
        } else {
            wifiShareManager.startSharing()
        }
    }

    // ── Notification status ────────────────────────────────────────────────

    private fun updateNotifStatus() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            notifStatusText.text = "Notifications forwarding to glasses"
            notifStatusText.setTextColor(0xFF66BB6A.toInt())
            btnNotifAccess.text = "Granted"
            btnNotifAccess.isEnabled = false
        } else {
            notifStatusText.text = "Show phone notifications on glasses"
            notifStatusText.setTextColor(0xFF757575.toInt())
            btnNotifAccess.text = "Grant"
            btnNotifAccess.isEnabled = true
        }
    }

    // ── Permissions & streaming ────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_PERMISSIONS)
        } else {
            startStreaming()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        when (rc) {
            RC_PERMISSIONS -> {
                if (results.all { it == PackageManager.PERMISSION_GRANTED }) startStreaming()
            }
            RC_WIFI_PERM -> {
                if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                    wifiShareManager.startSharing()
                } else {
                    switchWifiShare.isChecked = false
                    Toast.makeText(this, "Wi-Fi permissions required", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startStreaming() {
        val intent = Intent(this, HudStreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        streaming = true
        updateStreamingUi()
        statusText.text = "Streaming active - waiting for glasses"
        btAudioRouter.connectAudio()
        promptBatteryOptimizationIfNeeded()
    }

    private fun stopStreaming() {
        stopNavigation()
        service?.stopNavigation()
        service?.uiCallback = null
        service?.onClientConnectionChanged = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        stopService(Intent(this, HudStreamingService::class.java).apply {
            action = HudStreamingService.ACTION_STOP_STREAMING
        })
        streaming = false
        updateGlassesStatus()
        updateStreamingUi()
        statusText.text = "Streaming stopped"
        btAudioRouter.release()
        btAudioRouter.init()
    }

    private fun promptBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle("Keep running when screen is off")
            .setMessage("To keep maps and directions updating on your glasses when the phone screen turns off, allow this app to run in the background. Tap \"Allow\" below and turn off battery optimization for this app.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    val i = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(i)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ── Misc ───────────────────────────────────────────────────────────────

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, HudNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun speakNavInstruction(instruction: String, distance: Double) {
        if (!getPreferences(MODE_PRIVATE).getBoolean(PREF_TTS, false)) return
        btAudioRouter.speak(instruction, distance, isImperial())
    }

    private fun sendCurrentSettings() {
        val prefs = getPreferences(MODE_PRIVATE)
        val hudPrefs = getSharedPreferences(PREFS_HUD, MODE_PRIVATE)
        service?.sendSettings(
            ttsEnabled = prefs.getBoolean(PREF_TTS, false),
            useImperial = prefs.getBoolean(PREF_IMPERIAL, false),
            useMiniMap = prefs.getBoolean(PREF_MINI_MAP, false),
            miniMapStyle = prefs.getString(PREF_MINI_MAP_STYLE, "strip") ?: "strip",
            streamNotifications = hudPrefs.getBoolean(PREF_STREAM_NOTIFICATIONS, true),
            showUpcomingSteps = hudPrefs.getBoolean(PREF_SHOW_FULL_ROUTE_STEPS, false),
            showTurnAlert = hudPrefs.getBoolean(PREF_TURN_ALERT, false),
            tileCacheSizeMb = hudPrefs.getInt(PREF_TILE_CACHE_SIZE_MB, 100),
            showSpeed = hudPrefs.getBoolean(PREF_SHOW_SPEED, true),
            showSpeedLimit = hudPrefs.getBoolean(PREF_SHOW_SPEED_LIMIT, true)
        )
    }

    private fun setRouteModeQuick(mode: RouteMode) {
        if (ProviderPrefs.getRouteMode(this) == mode) {
            updateRouteModeQuickButtons()
            return
        }
        ProviderPrefs.setRouteMode(this, mode)
        syncingUiState = true
        spinnerRouteMode.setSelection(RouteMode.entries.indexOf(mode).coerceAtLeast(0), false)
        syncingUiState = false
        onRouteModeChanged(mode)
    }

    private fun onRouteModeChanged(mode: RouteMode) {
        if (mode != RouteMode.TRANSIT) {
            currentTransitRoute = null
            currentTransitOptions = emptyList()
            selectedTransitOptionIndex = 0
        }
        updateRouteModeQuickButtons()
        updateProviderStatus()
        selectedDest?.let {
            routeInfoText.text = "Preview route or start ${mode.label.lowercase()} navigation"
        }
    }

    private fun setHudLayoutQuick(useMiniMap: Boolean, miniMapStyle: String) {
        getPreferences(MODE_PRIVATE).edit()
            .putBoolean(PREF_MINI_MAP, useMiniMap)
            .putString(PREF_MINI_MAP_STYLE, miniMapStyle)
            .apply()

        syncingUiState = true
        switchMiniMap.isChecked = useMiniMap
        if (miniMapStyle == "split") {
            radioSplit.isChecked = true
        } else {
            radioStrip.isChecked = true
        }
        miniMapStyleGroup.visibility = View.GONE
        syncingUiState = false

        updateHudLayoutButtons()
        sendCurrentSettings()
    }

    private fun toggleAdvancedSettings() {
        advancedSettingsExpanded = !advancedSettingsExpanded
        advancedSettingsPanel.visibility = if (advancedSettingsExpanded) View.VISIBLE else View.GONE
        updateAdvancedToggle()
    }

    private fun updateAdvancedToggle() {
        btnAdvancedToggle.text = if (advancedSettingsExpanded) {
            "Hide Advanced Settings"
        } else {
            "Show Advanced Settings"
        }
    }

    private fun updateRouteModeQuickButtons() {
        when (ProviderPrefs.getRouteMode(this)) {
            RouteMode.DRIVE -> {
                styleQuickActionButton(btnRouteDrive, true)
                styleQuickActionButton(btnRouteWalk, false)
                styleQuickActionButton(btnRouteTransit, false)
            }
            RouteMode.WALK -> {
                styleQuickActionButton(btnRouteDrive, false)
                styleQuickActionButton(btnRouteWalk, true)
                styleQuickActionButton(btnRouteTransit, false)
            }
            RouteMode.TRANSIT -> {
                styleQuickActionButton(btnRouteDrive, false)
                styleQuickActionButton(btnRouteWalk, false)
                styleQuickActionButton(btnRouteTransit, true)
            }
        }
    }

    private fun updateHudLayoutButtons() {
        val prefs = getPreferences(MODE_PRIVATE)
        val useMiniMap = prefs.getBoolean(PREF_MINI_MAP, false)
        val style = prefs.getString(PREF_MINI_MAP_STYLE, "strip") ?: "strip"
        styleQuickActionButton(btnHudFull, !useMiniMap)
        styleQuickActionButton(btnHudStrip, useMiniMap && style != "split")
        styleQuickActionButton(btnHudSplit, useMiniMap && style == "split")
    }

    private fun styleQuickActionButton(button: Button, selected: Boolean) {
        val backgroundColor = if (selected) "#00E676" else "#2A2A2A"
        val textColor = if (selected) "#000000" else "#E0E0E0"
        button.backgroundTintList = ColorStateList.valueOf(Color.parseColor(backgroundColor))
        button.setTextColor(Color.parseColor(textColor))
    }

    private fun updateProviderStatus() {
        val error = ProviderRegistry.validateGoogleSelection(this)
        providerStatusText.text = when {
            error != null -> error
            else -> "Search: ${ProviderPrefs.currentSearchProviderLabel(this)} | Route: ${ProviderPrefs.currentRouteProviderLabel(this)} | Mode: ${ProviderPrefs.currentRouteModeLabel(this)}"
        }
    }

    private fun setupRouteModeSpinner() {
        val modes = RouteMode.entries
        spinnerRouteMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.label }
        )
        val current = ProviderPrefs.getRouteMode(this)
        spinnerRouteMode.setSelection(modes.indexOf(current).coerceAtLeast(0), false)
        spinnerRouteMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (syncingUiState) return
                val mode = modes[position]
                ProviderPrefs.setRouteMode(this@MainActivity, mode)
                onRouteModeChanged(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupCacheSpinner() {
        val sizes = listOf(50, 100, 200, 500)
        val labels = sizes.map { "$it MB" }
        spinnerCacheSize.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val savedSize = getSharedPreferences(PREFS_HUD, MODE_PRIVATE).getInt(PREF_TILE_CACHE_SIZE_MB, 100)
        val idx = sizes.indexOf(savedSize).coerceAtLeast(0)
        spinnerCacheSize.setSelection(idx)
        spinnerCacheSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val mb = sizes[pos]
                getSharedPreferences(PREFS_HUD, MODE_PRIVATE).edit().putInt(PREF_TILE_CACHE_SIZE_MB, mb).apply()
                service?.updateTileCacheSize(mb)
                sendCurrentSettings()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnClearCache.setOnClickListener {
            service?.clearTileCache()
            Toast.makeText(this, "Map cache cleared", Toast.LENGTH_SHORT).show()
            updateCacheSizeText()
        }
        updateCacheSizeText()
    }

    private fun updateCacheSizeText() {
        val bytes = service?.tileCacheSizeBytes() ?: 0L
        val mb = bytes / (1024.0 * 1024.0)
        cacheSizeText.text = String.format("Used: %.1f MB", mb)
    }

    private fun isImperial(): Boolean = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL, false)

    private fun formatDist(m: Double): String = if (isImperial()) {
        val feet = m * 3.28084
        val miles = m / 1609.344
        when {
            miles >= 0.1 -> String.format("%.1f mi", miles)
            else -> String.format("%.0f ft", feet)
        }
    } else {
        when {
            m >= 1000 -> String.format("%.1f km", m / 1000)
            else -> String.format("%.0f m", m)
        }
    }

    private fun formatTime(s: Double): String {
        val mins = (s / 60).toInt()
        return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins} min"
    }
}
