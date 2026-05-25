package com.anezium.rokidgmaps.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.anezium.rokidgmaps.shared.protocol.PoiInfo
import com.anezium.rokidgmaps.shared.protocol.StepInfo
import com.anezium.rokidgmaps.shared.protocol.Waypoint
import java.io.File
import java.util.Locale

class HudActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val RC_BT = 200
        private const val TAG = "HudActivity"
        private const val ROKID_DOUBLE_BACK_ACTION = "com.rokid.glass.homekey.doubleback"
        private const val EXIT_ON_STOP_DELAY_MS = 400L
        private const val CLOSING_MESSAGE_DURATION_MS = 1800L
        private const val CLOSING_MESSAGE = "Rokid Maps is closing"
        private const val INPUT_TOGGLE_DEBOUNCE_MS = 600L
    }

    private lateinit var hudView: HudView
    private lateinit var btClient: BluetoothClient
    private lateinit var tileManager: TileManager
    private lateinit var wifiConnector: WifiConnector

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenInstruction = ""

    // Set true when we start another activity so onStop does not exit the app.
    private var launchingExternalActivity = false
    // Set true when we are already closing so shutdown is not triggered twice.
    private var isShuttingDown = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var exitWhenStoppedRunnable: Runnable? = null
    private var lastLayoutToggleAtMs = 0L
    private var lastContentToggleAtMs = 0L
    private var lastRemoteLayoutMode = MapLayoutMode.FULL_SCREEN

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (scale > 0) (level * 100) / scale else -1
            hudView.state = hudView.state.copy(batteryLevel = pct)
        }
    }

    private val doubleBackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ROKID_DOUBLE_BACK_ACTION) {
                Log.i(TAG, "Double-back broadcast received, shutting down")
                shutdownApp()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hud)
        goFullscreen()

        hudView = findViewById(R.id.hudView)

        tileManager = TileManager(applicationContext) { hudView.postInvalidate() }
        hudView.tileManager = tileManager

        hudView.onLayoutToggle = { toggleContentView() }
        hudView.onContentViewToggle = { toggleContentView() }
        hudView.onDirectionalSwipe = { forward -> handleDirectionalSwipe(forward) }
        hudView.onDoubleTap = { shutdownApp() }

        tts = TextToSpeech(this, this)
        wifiConnector = WifiConnector(applicationContext)
        wifiConnector.onStatusChanged = { wifiConnected ->
            Log.i(TAG, "Wi-Fi status changed: connected=$wifiConnected")
            runOnUiThread {
                hudView.state = hudView.state.copy(wifiConnected = wifiConnected)
            }
        }

        btClient = BluetoothClient(
            context = this,
            onStateUpdate = { newState ->
                runOnUiThread {
                    val currentState = hudView.state
                    val remoteLayoutChanged = newState.layoutMode != lastRemoteLayoutMode
                    lastRemoteLayoutMode = newState.layoutMode
                    hudView.state = newState.copy(
                        batteryLevel = currentState.batteryLevel,
                        wifiConnected = currentState.wifiConnected,
                        layoutMode = if (remoteLayoutChanged) newState.layoutMode else currentState.layoutMode,
                        contentView = if (currentState.contentView == HudContentView.TRANSIT_RECAP && !newState.hasTransitContent()) {
                            HudContentView.HUD
                        } else {
                            currentState.contentView
                        }
                    )
                    tileManager.onTileRequestViaProxy = if (newState.btConnected) {
                        { z, x, y, id -> btClient.sendTileRequest(z, x, y, id) }
                    } else {
                        null
                    }
                    if (newState.tileCacheSizeMb != hudView.state.tileCacheSizeMb) {
                        tileManager.updateCacheSize(newState.tileCacheSizeMb)
                    }
                    if (newState.ttsEnabled &&
                        ttsReady &&
                        newState.instruction.isNotBlank() &&
                        newState.instruction != lastSpokenInstruction
                    ) {
                        lastSpokenInstruction = newState.instruction
                        speakInstruction(newState.instruction, newState.stepDistance, newState.useImperial)
                    }
                }
            },
            onWifiCreds = { ssid, pass, enabled ->
                if (enabled && ssid.isNotBlank()) {
                    Log.i(TAG, "Auto-connecting to Wi-Fi: $ssid")
                    wifiConnector.connect(ssid, pass)
                } else {
                    Log.i(TAG, "Disconnecting shared Wi-Fi")
                    wifiConnector.disconnect()
                }
            }
        )
        btClient.onTileReceived = { id, data -> tileManager.deliverTile(id, data) }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(doubleBackReceiver, IntentFilter(ROKID_DOUBLE_BACK_ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(doubleBackReceiver, IntentFilter(ROKID_DOUBLE_BACK_ACTION))
        }
        if (!applyDebugPreviewFromIntent(intent)) {
            requestPermissionsAndStartBt()
        }
    }

    override fun onStart() {
        super.onStart()
        exitWhenStoppedRunnable?.let { mainHandler.removeCallbacks(it) }
        exitWhenStoppedRunnable = null
        launchingExternalActivity = false
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDebugPreviewFromIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (launchingExternalActivity || isFinishing) return
        val runnable = Runnable {
            Log.i(TAG, "App moved to background, shutting down")
            shutdownApp(showMessage = false)
        }
        exitWhenStoppedRunnable = runnable
        mainHandler.postDelayed(runnable, EXIT_ON_STOP_DELAY_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    hudView.suppressViewTouchGestures()
                    Log.i(TAG, "Temple pad tap detected -> next view")
                    toggleContentView(forward = true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    hudView.suppressViewTouchGestures()
                    Log.i(TAG, "Temple pad swipe key fallback detected -> previous/scroll up")
                    handleDirectionalSwipe(forward = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    hudView.suppressViewTouchGestures()
                    Log.i(TAG, "Temple pad swipe key fallback detected -> next/scroll down")
                    handleDirectionalSwipe(forward = true)
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    hudView.suppressViewTouchGestures()
                    if (hudView.state.normalizedContentView() != HudContentView.HUD) {
                        Log.i(TAG, "Back detected -> return to HUD")
                        hudView.state = hudView.state.copy(contentView = HudContentView.HUD)
                        return true
                    }
                    Log.i(TAG, "Temple pad double-tap/back detected -> shutdown")
                    shutdownApp()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let {
            Log.d(TAG, "Temple pad motion: action=${it.actionMasked}, source=0x${it.source.toString(16)}")
            hudView.suppressViewTouchGestures()
            if (hudView.handleTemplePadMotionEvent(it)) {
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        exitWhenStoppedRunnable?.let { mainHandler.removeCallbacks(it) }
        try { unregisterReceiver(doubleBackReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        btClient.stop()
        wifiConnector.disconnect()
        tileManager.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (ttsReady) {
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.w(TAG, "TTS language not supported")
            }
        } else {
            Log.w(TAG, "TTS init failed with status $status")
        }
    }

    private fun speakInstruction(instruction: String, distance: Double, useImperial: Boolean) {
        if (!ttsReady) return
        val distText = if (useImperial) {
            val feet = distance * 3.28084
            val miles = distance / 1609.344
            when {
                miles >= 0.1 -> String.format("in %.1f miles", miles)
                feet >= 50 -> "in ${feet.toInt()} feet"
                distance > 0 -> "now"
                else -> ""
            }
        } else {
            when {
                distance >= 1000 -> String.format("in %.1f kilometers", distance / 1000)
                distance >= 50 -> "in ${distance.toInt()} meters"
                distance > 0 -> "now"
                else -> ""
            }
        }
        val speech = if (distText.isNotBlank()) "$instruction, $distText" else instruction
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "nav_step")
    }

    private fun toggleLayout() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLayoutToggleAtMs < INPUT_TOGGLE_DEBOUNCE_MS) {
            Log.d(TAG, "Ignoring duplicate layout toggle")
            return
        }
        lastLayoutToggleAtMs = now
        hudView.state = hudView.state.toggleLayout()
    }

    private fun toggleContentView(forward: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastContentToggleAtMs < INPUT_TOGGLE_DEBOUNCE_MS) {
            Log.d(TAG, "Ignoring duplicate content toggle")
            return
        }
        lastContentToggleAtMs = now
        hudView.state = hudView.state.cycleContentView(forward)
    }

    private fun handleDirectionalSwipe(forward: Boolean) {
        if (hudView.handleTransitSwipe(forward)) {
            Log.d(TAG, "Transit recap swipe consumed forward=$forward")
            return
        }
        toggleContentView(forward)
    }

    private fun shutdownApp(showMessage: Boolean = true) {
        if (isShuttingDown) return
        isShuttingDown = true
        exitWhenStoppedRunnable?.let { mainHandler.removeCallbacks(it) }
        exitWhenStoppedRunnable = null
        if (showMessage && !isFinishing) {
            hudView.state = hudView.state.copy(closingMessage = CLOSING_MESSAGE)
            mainHandler.postDelayed({
                doShutdown()
            }, CLOSING_MESSAGE_DURATION_MS)
        } else {
            doShutdown()
        }
    }

    private fun doShutdown() {
        Log.i(TAG, "Shutting down app")
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun installReceivedApk(file: File) {
        try {
            launchingExternalActivity = true
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Install intent started for received APK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer: ${e.message}")
        }
    }

    private fun requestPermissionsAndStartBt() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CHANGE_WIFI_STATE)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), RC_BT)
        }
        btClient.start()
        Log.i(TAG, "Bluetooth client started (permissions pending: ${needed.size})")
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        Log.i(TAG, "Permissions result: rc=$rc granted=${results.count { it == PackageManager.PERMISSION_GRANTED }}/${results.size}")
    }

    private fun applyDebugPreviewFromIntent(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val requested = intent?.getStringExtra("debugView")?.lowercase(Locale.US) ?: return false
        val view = when (requested) {
            "transit", "trn" -> HudContentView.TRANSIT_RECAP
            "map", "fullmap", "full_map" -> HudContentView.FULL_MAP
            "hud", "compact" -> HudContentView.HUD
            else -> return false
        }
        Log.i(TAG, "Applying debug HUD preview: $requested")
        hudView.state = buildDebugPreviewState(view)
        return true
    }

    private fun buildDebugPreviewState(view: HudContentView): HudState {
        val route = listOf(
            Waypoint(48.8584, 2.2945),
            Waypoint(48.8575, 2.2896),
            Waypoint(48.8466, 2.2952),
            Waypoint(48.8422, 2.3124),
            Waypoint(48.8443, 2.3285),
            Waypoint(48.8584, 2.3470),
            Waypoint(48.8619, 2.3470)
        )
        val steps = listOf(
            StepInfo("Walk to Avenue de Suffren", "straight", 80.0),
            StepInfo("Continue on Avenue de Suffren toward Bir-Hakeim station", "right", 155.0),
            StepInfo("Walk to Bir-Hakeim station", "straight", 185.0),
            StepInfo("Take Metro 6 toward Nation from Bir-Hakeim to Charles de Gaulle - Etoile (5 stops)", "transit", 4600.0),
            StepInfo("Take RER A toward Boissy-Saint-Leger from Charles de Gaulle - Etoile to Chatelet Les Halles (3 stops)", "transit", 3900.0),
            StepInfo("Walk to Rue de Rivoli", "right", 110.0),
            StepInfo("Continue on Rue de Rivoli toward McDonald's", "straight", 150.0)
        )
        val pois = listOf(
            PoiInfo("Tour Eiffel", 48.8584, 2.2945, "landmark", 95),
            PoiInfo("Trocadero", 48.8627, 2.2876, "landmark", 82),
            PoiInfo("Invalides", 48.8566, 2.3126, "landmark", 78),
            PoiInfo("Louvre", 48.8606, 2.3376, "landmark", 88),
            PoiInfo("Chatelet", 48.8584, 2.3470, "station", 84),
            PoiInfo("McDonald's", 48.8609, 2.3468, "food", 62)
        )
        return HudState(
            latitude = 48.8584,
            longitude = 2.2945,
            bearing = 74f,
            speed = 1.2f,
            accuracy = 4f,
            waypoints = route,
            totalDistance = 9180.0,
            totalDuration = 1860.0,
            instruction = "Walk to Bir-Hakeim station",
            maneuver = "straight",
            stepDistance = 420.0,
            routeMode = "transit",
            showLandmarks = true,
            landmarks = pois,
            allSteps = steps,
            currentStepIndex = 0,
            batteryLevel = 100,
            btConnected = true,
            wifiConnected = true,
            distToNextStep = 120.0,
            showSpeed = true,
            showSpeedLimit = false,
            contentView = view
        )
    }

    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.decorView?.let { decor ->
                    decor.windowInsetsController?.let { ctrl ->
                        ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } catch (_: Exception) {}
    }
}
