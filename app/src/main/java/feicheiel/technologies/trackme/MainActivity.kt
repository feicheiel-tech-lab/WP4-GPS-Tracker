package feicheiel.technologies.trackme

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import feicheiel.technologies.trackme.ui.theme.PROJECT_Red
import feicheiel.technologies.trackme.ui.theme.PROJECT_Yellow
import feicheiel.technologies.trackme.ui.theme.TrackMeTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

import java.io.File
import java.io.FileOutputStream
import org.osmdroid.views.overlay.CopyrightOverlay
import androidx.compose.ui.platform.ComposeView

import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.CloudDownload
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.graphics.drawscope.Fill
//import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {

//    private var isLoggingOut = false

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val userId = if (Constants.IS_TEST) "${getDeviceID()} [test-data]" else getDeviceID()
                val importedCount = importCsvFromUri(this@MainActivity, it, userId)
                if (importedCount > 0) {
                    Toast.makeText(this@MainActivity, "Imported $importedCount new points", Toast.LENGTH_SHORT).show()

                    // Tell the running ForeGroundService to re-read the DB and refresh
                    // its notification. Without this, the notification's "X km | Y pts"
                    // line stays stuck on pre-import values until the next GPS fix
                    // arrives — because that's the only path that calls updateStatsUI().
                    Intent(this@MainActivity, ForeGroundService::class.java).also { refresh ->
                        refresh.action = ForeGroundService.Actions.UPDATE.toString()
                        // startService is fine here: the service is already running in
                        // the foreground, so this is a no-op start that just delivers
                        // the action to onStartCommand().
                        startService(refresh)
                    }
                } else if (importedCount == 0) {
                    Toast.makeText(this@MainActivity, "No new points to import", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Import failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initiateImport() {
        openDocumentLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv"))
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val database = AppDatabase.getDatabase(this@MainActivity)
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val userId = if (Constants.IS_TEST) "${getDeviceID()} [test-data]" else getDeviceID()
                val points = database.locationDao().getAllPoints(userId)

                if (writeCsvToUri(this@MainActivity, it, points)) {
                    Toast.makeText(this@MainActivity, "Export successful", Toast.LENGTH_SHORT).show()
//                    if (isLoggingOut) {
//                        performFinalLogout(userId)
//                    }
                } else {
                    Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getDeviceID(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

    private fun initiateExport() {
//        isLoggingOut = false
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val userId = if (Constants.IS_TEST) "${getDeviceID()} [test-data]" else getDeviceID()
        createDocumentLauncher.launch("trackme_export_${userId}_$timestamp.csv")
    }

//    private fun initiateLogout() {
//        lifecycleScope.launch {
//            val database = AppDatabase.getDatabase(this@MainActivity)
//            val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
//            val userId = if (Constants.IS_TEST) "${getDeviceID()} [test-data]" else getDeviceID()
//
//            val unsyncedCount = database.locationDao().getUnsyncedCount(userId)
//            if (unsyncedCount > 0) {
//                Toast.makeText(this@MainActivity, "Uploading $unsyncedCount unsynced points before logout...", Toast.LENGTH_SHORT).show()
//                val intent = Intent(this@MainActivity, SyncForegroundService::class.java).apply {
//                    action = SyncForegroundService.ACTION_UPLOAD
//                }
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    startForegroundService(intent)
//                } else {
//                    startService(intent)
//                }
//            }
//
//            isLoggingOut = true
//            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
//            createDocumentLauncher.launch("trackme_export_${userId}_$timestamp.csv")
//        }
//    }
//
//    private fun performFinalLogout(userId: String) {
//        // Stop the service
//        Intent(this, ForeGroundService::class.java).also {
//            it.action = ForeGroundService.Actions.STOP.toString()
//            startService(it)
//        }
//
//        lifecycleScope.launch {
//            val database = AppDatabase.getDatabase(this@MainActivity)
//            database.locationDao().deleteAll(userId)
//
//            getSharedPreferences("auth", Context.MODE_PRIVATE).edit().clear().apply()
//            getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().clear().apply()
//
//            finish()
//        }
//    }

    // Step 1: Request fine location (+ notifications). After granting, we separately ask
    // for background location because Android 11+ forbids bundling both in one dialog.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            startTrackingService()
            requestIgnoreBatteryOptimizations()
            // Step 2: now that fine location is granted, ask for background location.
            // This must be a separate request — the system rejects it if bundled with fine location.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    // Step 2: Standalone background-location request — must be separate on Android 11+.
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Background location denied — tracking may stop when the screen turns off",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable true edge-to-edge fullscreen mode
        enableEdgeToEdge()

        val userPrefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        var userId = userPrefs.getString("current_user_id", null)
        if (userId == null) {
            userId = if (Constants.IS_TEST) "${getDeviceID()} [test-data]" else getDeviceID()
            userPrefs.edit().putString("current_user_id", userId).apply()
            // Also clear the old "auth" preference if it exists
            getSharedPreferences("auth", Context.MODE_PRIVATE).edit().clear().apply()
        }

        // Use internal storage for osmdroid so SQLite WAL-mode ioctls (FS_IOC_GETFLAGS)
        // work on all devices. External FUSE storage blocks these ioctls via SELinux on
        // non-Samsung phones (permissive=0), causing SQLite errors and tile-cache corruption.
        val _basePath = File(filesDir, "osmdroid")
        _basePath.mkdirs()
        Configuration.getInstance().osmdroidBasePath = _basePath
        Configuration.getInstance().osmdroidTileCache = File(_basePath, "tiles")

        if (hasRequiredPermissions()) {
            startTrackingService()
            requestIgnoreBatteryOptimizations()
        } else {
            launchPermissionRequest()
        }

        Configuration.getInstance().userAgentValue = packageName
        val osmConfig = getSharedPreferences("osm", MODE_PRIVATE)
        Configuration.getInstance().load(this, osmConfig)

        // Use app-specific files directory for osmdroid to avoid permission issues on Android 13+
        val basePath = File(getExternalFilesDir(null), "osmdroid")
        Configuration.getInstance().osmdroidBasePath = basePath
        Configuration.getInstance().osmdroidTileCache = File(basePath, "tiles")

        // Copy offline map from assets if it exists
        copyOfflineMapFromAssets("aoi.mbtiles")

        setContent {
            TrackMeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Fullscreen map: map draws behind status/nav bars. 
                    // UI elements inside OSMMapScreen handle their own safe-area offsets.
                    OSMMapScreen(
                        modifier = Modifier.fillMaxSize(),
                        onExport = { initiateExport() },
                        onImport = { initiateImport() },
                        userId = userId
                    )
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineLoc = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // On Android 10+ the service needs background location so it can restart
        // after being killed by the OS without losing location access.
        val bgLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true // not a concept before Android 10

        return fineLoc && bgLoc
    }

    private fun launchPermissionRequest() {
        // Only request dangerous runtime permissions here.
        // FOREGROUND_SERVICE_LOCATION is a normal permission — auto-granted from the manifest,
        // adding it to this batch causes the whole request to fail on some Android 14 builds.
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
        // Background location is requested in step 2 inside requestPermissionLauncher callback.
    }

    private fun startTrackingService() {
        Intent(this, ForeGroundService::class.java).also {
            it.action = ForeGroundService.Actions.START.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun copyOfflineMapFromAssets(fileName: String) {
        val destinationFile = File(Configuration.getInstance().osmdroidBasePath, fileName)
        if (!destinationFile.exists()) {
            try {
                destinationFile.parentFile?.mkdirs()
                assets.open(fileName).use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}

@Composable
fun OSMMapScreen(
    modifier: Modifier = Modifier,
//    onLogout: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    userId: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }

    val isSyncing by SyncForegroundService.isSyncing.collectAsState(initial = false)

    // Attempt to get last known location for initial center
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val location by ForeGroundService.currentLocation.collectAsState()
    val status by ForeGroundService.currentStatus.collectAsState()
    val isServiceRunning by ForeGroundService.isRunning.collectAsState()
    val isPaused by ForeGroundService.isPaused.collectAsState()
    val syncedCount by database.locationDao().getSyncedCountFlow(userId).collectAsState(initial = 0)
    val unsyncedCount by database.locationDao().getUnsyncedCountFlow(userId).collectAsState(initial = 0)

    val allPointsFlow = remember(userId) {
        database.locationDao().getAllPointsFlow(userId)
    }
    val allPoints by allPointsFlow.collectAsState(initial = emptyList())

    val mapViewInstance = remember { mutableStateOf<MapView?>(null) }

    // We use a single ComposeView for the indicator and keep it alive to preserve animations.
    val compositionContext = rememberCompositionContext()
    val indicatorComposeView = remember {
        ComposeView(context).apply {
            setParentCompositionContext(compositionContext)
        }
    }
    // State to track status for the indicator without re-triggering setContent
    val currentStatusState = remember { mutableStateOf(ForeGroundService.Status.SEARCHING) }

    // Initialize the indicator content
    DisposableEffect(indicatorComposeView) {
        indicatorComposeView.setContent {
            TrackMeTheme {
                LocationGlowIndicator(currentStatusState.value)
            }
        }
        onDispose { }
    }

    var isDrawTracksEnabled by remember { mutableStateOf(true) }
    var selectedDuration by remember { mutableStateOf("All") }
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var isDurationMenuExpanded by remember { mutableStateOf(false) }
    var isFollowingUser by remember { mutableStateOf(true) }
    
    var selectedPauseDuration by remember { mutableStateOf("1 hour") }
    var isPauseMenuExpanded by remember { mutableStateOf(false) }

    // Initial map centering
    LaunchedEffect(Unit) {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null && location == null) {
                    mapViewInstance.value?.controller?.apply {
                        setCenter(GeoPoint(lastLoc.latitude, lastLoc.longitude))
                        setZoom(17.0)
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    var isPanelExpanded by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val screenHeight = configuration.screenHeightDp.dp
    val expandedHeight = screenHeight * 0.37f
    val collapsedHeight = 72.dp // Raised to clear system navigation bars in fullscreen mode

    val panelHeight by animateDpAsState(
        targetValue = if (isPanelExpanded) expandedHeight else collapsedHeight,
        label = "panelHeight"
    )
    val fabPadding by animateDpAsState(
        targetValue = panelHeight + 12.dp,
        label = "fabPadding"
    )

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewInstance.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewInstance.value?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Use default OSM tiles for clarity
    val mapTileSource = TileSourceFactory.MAPNIK

    // 1. Persistent polyline cache: segmentKey -> Polyline, survives recompositions.
    //    Key format: "<dayKey>:<segmentIndex>" e.g. "2025-01-15:0", "2025-01-15:1"
    //    Only the last segment of today can grow; all others are sealed.
    data class SegmentState(val polyline: Polyline, val pointCount: Int)
    val polylineCache = remember { mutableMapOf<String, SegmentState>() }
    // Tracks which keys were already added to the MapView.
    val overlaysOnMap = remember { mutableSetOf<String>() }
    // New segment keys to be added to the MapView in the next AndroidView update pass.
    val pendingAddKeys = remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(allPoints, isDrawTracksEnabled, selectedDuration) {
        if (!isDrawTracksEnabled || allPoints.isEmpty()) {
            polylineCache.clear()
            overlaysOnMap.clear()
            pendingAddKeys.value = emptyList()
            return@LaunchedEffect
        }

        // --- BACKGROUND: pure data work, no View or Polyline mutations ---
        // Snapshot values that the background thread will read, so they can't
        // change mid-computation if the composable recomposes while we're off-thread.
        val pointsSnapshot = allPoints
        val durationSnapshot = selectedDuration

        data class SegmentUpdate(
            val segKey: String,
            val dayKey: String,
            val color: Int,
            val geoPoints: List<GeoPoint>,    // full point list for new segments
            val appendPoints: List<GeoPoint>, // only new tail points for active segment
            val totalCount: Int,
            val isNew: Boolean,               // true = create new Polyline; false = append
            val isToday: Boolean              // today's track draws on top; past gets 0.5 alpha
        )

        val updates: List<SegmentUpdate> = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val todayKey = sdf.format(Date(now))

            val filteredPoints = pointsSnapshot.filter {
                when (durationSnapshot) {
                    "Last Hour" -> it.timestamp > now - 3600000
                    "Last 24h"  -> it.timestamp > now - 86400000
                    else        -> true
                }
            }

            if (filteredPoints.isEmpty()) return@withContext emptyList()

            val pointsByDay = filteredPoints.groupBy { sdf.format(Date(it.timestamp)) }
            val result = mutableListOf<SegmentUpdate>()

            pointsByDay.forEach { (dayKey, dayPoints) ->
                val isToday = dayKey == todayKey
                val color = if (isToday)
                    android.graphics.Color.parseColor("#FF4500")
                else
                    android.graphics.Color.parseColor("#89C9F7")

                // Build continuous segments (gap > 1 min → new segment).
                val continuousSegments = mutableListOf<MutableList<LocationEntity>>()
                if (dayPoints.isNotEmpty()) {
                    var current = mutableListOf(dayPoints[0])
                    continuousSegments.add(current)
                    for (i in 1 until dayPoints.size) {
                        if (dayPoints[i].timestamp - dayPoints[i - 1].timestamp > 60_000L) {
                            current = mutableListOf(dayPoints[i])
                            continuousSegments.add(current)
                        } else {
                            current.add(dayPoints[i])
                        }
                    }
                }

                continuousSegments.forEachIndexed { segIndex, segment ->
                    if (segment.size < 2) return@forEachIndexed
                    val segKey = "$dayKey:$segIndex"
                    val isActiveSegment = isToday && segIndex == continuousSegments.lastIndex
                    // Read pointCount from cache — safe here because only Main thread writes it.
                    val cachedCount = polylineCache[segKey]?.pointCount ?: 0

                    when {
                        cachedCount == 0 -> {
                            // New segment: pre-build the full GeoPoint list off-thread.
                            result.add(SegmentUpdate(
                                segKey       = segKey,
                                dayKey       = dayKey,
                                color        = color,
                                geoPoints    = segment.map { GeoPoint(it.latitude, it.longitude) },
                                appendPoints = emptyList(),
                                totalCount   = segment.size,
                                isNew        = true,
                                isToday      = isToday
                            ))
                        }
                        isActiveSegment && segment.size > cachedCount -> {
                            // Active segment grew: pre-build only the new tail off-thread.
                            result.add(SegmentUpdate(
                                segKey       = segKey,
                                dayKey       = dayKey,
                                color        = color,
                                geoPoints    = emptyList(),
                                appendPoints = segment.drop(cachedCount)
                                    .map { GeoPoint(it.latitude, it.longitude) },
                                totalCount   = segment.size,
                                isNew        = false,
                                isToday      = isToday
                            ))
                        }
                        // Sealed segment, nothing changed — skip.
                    }
                }
            }
            result
        }
        // --- MAIN THREAD: apply computed updates to Polyline objects and cache ---

        if (updates.isEmpty()) {
            // Filter may have narrowed to nothing — clear stale state.
            polylineCache.clear()
            overlaysOnMap.clear()
            pendingAddKeys.value = emptyList()
            return@LaunchedEffect
        }

        // Drop cached keys whose day fell outside the filter window.
        val validDayKeys = updates.map { it.dayKey }.toSet()
        polylineCache.keys.filter { it.substringBefore(":") !in validDayKeys }.forEach { key ->
            polylineCache.remove(key)
            overlaysOnMap.remove(key)
        }

        val newlyCreatedKeys = mutableListOf<String>()

        // Process new-segment updates sorted oldest-day-first so that when they
        // are added to the MapView overlay list, past tracks sit underneath and
        // today's track renders on top (last added = highest z-order in OSMDroid).
        val sortedUpdates = updates.sortedWith(compareBy({ it.isToday }, { it.segKey }))

        sortedUpdates.forEach { update ->
            if (update.isNew) {
                // Past-day tracks get 0.5 alpha (128/255) so today's track pops visually.
                val paintColor = if (update.isToday) {
                    update.color
                } else {
                    android.graphics.Color.argb(
                        128,
                        android.graphics.Color.red(update.color),
                        android.graphics.Color.green(update.color),
                        android.graphics.Color.blue(update.color)
                    )
                }
                // GeoPoints already built off-thread — just wire up the Polyline.
                val polyline = Polyline().apply {
                    setPoints(update.geoPoints)
                    outlinePaint.color = paintColor
                    outlinePaint.strokeWidth = 12f
                }
                polylineCache[update.segKey] = SegmentState(polyline, update.totalCount)
                newlyCreatedKeys.add(update.segKey)
            } else {
                // Tail GeoPoints already built off-thread — append and update count.
                val existing = polylineCache[update.segKey] ?: return@forEach
                existing.polyline.setPoints(existing.polyline.actualPoints + update.appendPoints)
                polylineCache[update.segKey] = existing.copy(pointCount = update.totalCount)
                // Already on the map; setPoints triggers its own redraw.
            }
        }

        pendingAddKeys.value = newlyCreatedKeys
    }

    // Panel stats — derived from allPoints but memoised so Compose only
    // recomposes the panel when the *output* actually changes, not on every
    // raw Flow emission that leaves these values identical.
    val totalDistanceKm by remember {
        derivedStateOf { (allPoints.lastOrNull()?.totalDistance ?: 0f) / 1000f }
    }
    val totalPoints by remember {
        derivedStateOf { allPoints.size }
    }
    val uniqueDays by remember {
        derivedStateOf {
            allPoints.map { sdf.format(Date(it.timestamp)) }.distinct().size
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = feicheiel.technologies.trackme.ui.theme.SourceSansProFontFamily
                    )
                    //Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.CloudSync, contentDescription = null) },
                    label = { Text("Upload to Server") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            val intent = Intent(context, SyncForegroundService::class.java).apply {
                                action = SyncForegroundService.ACTION_UPLOAD
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                    label = { Text("Download from Server") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            val intent = Intent(context, SyncForegroundService::class.java).apply {
                                action = SyncForegroundService.ACTION_DOWNLOAD
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    label = { Text("Export Tracks") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            onExport()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                    label = { Text("Import Tracks") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            onImport()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                //Spacer(Modifier.weight(1f))
//                NavigationDrawerItem(
//                    icon = { Icon(Icons.Default.Logout, contentDescription = null, tint = Color.Red) },
//                    label = { Text("Sign Out", color = Color.Red) },
//                    selected = false,
//                    onClick = {
//                        scope.launch {
//                            drawerState.close()
//                            onLogout()
//                        }
//                    },
//                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
//                )
            }
        }
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(mapTileSource)
                        setMultiTouchControls(true)
                        setUseDataConnection(true)
                        controller.setZoom(18.0)
                        mapViewInstance.value = this

                        clipChildren = false
                        clipToPadding = false

                        val rotationGestureOverlay = RotationGestureOverlay(this)
                        rotationGestureOverlay.isEnabled = true
                        overlays.add(rotationGestureOverlay)

                        val copyrightOverlay = CopyrightOverlay(ctx).apply {
                            setCopyrightNotice("© OpenStreetMap contributors")
                        }
                        overlays.add(copyrightOverlay)

                        setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                isFollowingUser = false
                            }
                            false
                        }
                    }
                },
                update = { view ->
                    currentStatusState.value = status

                    location?.let {
                        val userPoint = GeoPoint(it.latitude, it.longitude)

                        if (indicatorComposeView.parent == null) {
                            val lp = MapView.LayoutParams(
                                MapView.LayoutParams.WRAP_CONTENT,
                                MapView.LayoutParams.WRAP_CONTENT,
                                userPoint,
                                MapView.LayoutParams.CENTER,
                                0, 0
                            )
                            view.addView(indicatorComposeView, lp)

                            // Center and zoom on first location reception
                            view.controller.setCenter(userPoint)
                            view.controller.setZoom(18.0)
                        } else {
                            val lp = indicatorComposeView.layoutParams as MapView.LayoutParams
                            lp.geoPoint = userPoint
                            view.updateViewLayout(indicatorComposeView, lp)
                        }

                        if (isFollowingUser){
                            view.controller.animateTo(userPoint)
                        }
                    }

                    // 2. Incremental overlay update: only add brand-new segments; never remove/re-add.
                    //    Active segment appends are handled by setPoints() in the LaunchedEffect above,
                    //    which triggers its own redraw — no MapView.invalidate() needed for those.
                    val keysToAdd = pendingAddKeys.value
                    if (keysToAdd.isNotEmpty()) {
                        keysToAdd.forEach { key ->
                            polylineCache[key]?.polyline?.let { view.overlays.add(it) }
                            overlaysOnMap.add(key)
                        }
                        pendingAddKeys.value = emptyList()
                        view.invalidate()
                    }
                    // If tracks were toggled off or filter changed, clear all polyline overlays.
                    if (!isDrawTracksEnabled || polylineCache.isEmpty()) {
                        if (view.overlays.removeAll { it is Polyline }) {
                            overlaysOnMap.clear()
                            view.invalidate()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Menu Button (Top Left)
            IconButton(
                onClick = { scope.launch { drawerState.open() } },
                modifier = Modifier
                    .padding(top = 48.dp, start = 16.dp)
                    .size(48.dp)
                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
                    .align(Alignment.TopStart)
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.Black)
            }

            // SYNC STATUS & BUTTON OVERLAY
            val syncColor = if (unsyncedCount > 0) Color(0xFFFFD355) else Color(0xFF509FB7)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 20.dp)
                    .shadow(
                        elevation = 48.dp,
                        shape = RoundedCornerShape(24.dp),
                        spotColor = syncColor,
                        ambientColor = syncColor
                    )
                    .border(0.3.dp, syncColor.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
                color = Color.White,
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = {
                            scope.launch {
                                val intent = Intent(context, SyncForegroundService::class.java).apply {
                                    action = SyncForegroundService.ACTION_UPLOAD
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                        }
                    ) {
                        if (isSyncing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Rounded.CloudSync,
                                contentDescription = "Sync",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color.LightGray.copy(alpha = 0.5f))
                    )

                    Spacer(Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(syncColor, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                    )

                    Spacer(Modifier.width(12.dp))
                }
            }

            FloatingActionButton(
                onClick = {
                    isFollowingUser = true
                    val currentLoc = location
                    mapViewInstance.value?.let { map ->
                        map.mapOrientation = 0f
                        if (currentLoc != null) {
                            map.controller.animateTo(GeoPoint(currentLoc.latitude, currentLoc.longitude), 18.0, 1000L)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = fabPadding, end = 20.dp)
                    .shadow(
                        elevation = 53.dp,
                        shape = RoundedCornerShape(27.dp),
                        spotColor = MaterialTheme.colorScheme.primary,
                        ambientColor = MaterialTheme.colorScheme.primary
                    ),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Rounded.MyLocation, contentDescription = "Recenter")
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(panelHeight)
                    .shadow(
                        elevation = 60.dp,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                        spotColor = Color.Black.copy(alpha = 0.5f),
                        ambientColor = Color.Black.copy(alpha = 0.3f)
                    ),
                // Square bottom corners to sit flush with the screen edge
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Simplified Drag Handle: Thick, short bar with rounded ends
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(35.dp) // Explicit touch area
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { isPanelExpanded = !isPanelExpanded })
                            }
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    if (delta < -5) isPanelExpanded = true
                                    if (delta > 5) isPanelExpanded = false
                                },
                                onDragStopped = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 5.dp) // short, thick
                                .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.5.dp)) // rounded ends
                        )
                    }

                    if (isPanelExpanded) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
//                            Row(
//                                modifier = Modifier.fillMaxWidth(),
//                                horizontalArrangement = Arrangement.SpaceBetween,
//                                verticalAlignment = Alignment.CenterVertically
//                            ) {
//                                Column(modifier = Modifier.weight(1f)) {
//                                    Text(if (isPaused) "Tracking Paused" else "Pause Logging", style = MaterialTheme.typography.bodyMedium)
//                                    if (!isPaused) {
//                                        Box {
//                                            Text(
//                                                text = "Duration: $selectedPauseDuration",
//                                                style = MaterialTheme.typography.labelSmall,
//                                                color = Color(0xFFFFA726),//MaterialTheme.colorScheme.primary,
//                                                modifier = Modifier
//                                                    .pointerInput(Unit) {
//                                                        detectTapGestures(onTap = { isPauseMenuExpanded = true })
//                                                    }
//                                                    .padding(vertical = 4.dp)
//                                            )
//                                            DropdownMenu(
//                                                expanded = isPauseMenuExpanded,
//                                                onDismissRequest = { isPauseMenuExpanded = false }
//                                            ) {
//                                                listOf("1 min", "30 min", "1 hour").forEach { duration ->
//                                                    DropdownMenuItem(
//                                                        text = { Text(duration) },
//                                                        onClick = {
//                                                            selectedPauseDuration = duration
//                                                            isPauseMenuExpanded = false
//                                                        }
//                                                    )
//                                                }
//                                            }
//                                        }
//                                    }
//                                }
//                                Button(
//                                    onClick = {
//                                        val durationMs = when (selectedPauseDuration) {
//                                            "1 min" -> 60000L
//                                            "30 min" -> 1800000L
//                                            else -> 3600000L
//                                        }
//                                        val intent = Intent(context, ForeGroundService::class.java).apply {
//                                            action = if (isPaused) ForeGroundService.Actions.RESUME.toString() else ForeGroundService.Actions.PAUSE.toString()
//                                            if (!isPaused) {
//                                                putExtra("duration_ms", durationMs)
//                                                putExtra("duration_text", selectedPauseDuration)
//                                            }
//                                        }
//                                        context.startService(intent)
//                                    },
//                                    shape = RoundedCornerShape(12.dp),
//                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
//                                        containerColor = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer,
//                                        contentColor = if (isPaused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer
//                                    )
//                                ) {
//                                    Text(if (isPaused) "Resume Now" else "Pause for $selectedPauseDuration")
//                                }
//                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    val sourceSans =
                                        feicheiel.technologies.trackme.ui.theme.SourceSansProFontFamily
                                    val vibrantBlue = Color(0xFF89C9F7)

                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                                                append(
                                                    "Synced: "
                                                )
                                            }
                                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                append(
                                                    "$syncedCount"
                                                )
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = sourceSans),
                                        color = vibrantBlue
                                    )
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                                                append(
                                                    "Unsynced: "
                                                )
                                            }
                                            withStyle(
                                                SpanStyle(
                                                    fontWeight = FontWeight.Bold,
                                                    color = vibrantBlue
                                                )
                                            ) { append("$unsyncedCount") }
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = sourceSans),
                                        color = if (unsyncedCount > 0) PROJECT_Red else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                                                append(
                                                    "Total Points: "
                                                )
                                            }
                                            withStyle(
                                                SpanStyle(
                                                    fontWeight = FontWeight.Bold,
                                                    color = vibrantBlue
                                                )
                                            ) { append("$totalPoints") }
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = sourceSans)
                                    )
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                                    Surface(
                                        color = Color(0xFFD5E5F3),
                                        shape = CircleShape,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = String.format("%.2f km", totalDistanceKm),
                                            modifier = Modifier.padding(
                                                horizontal = 16.dp,
                                                vertical = 6.dp
                                            ),
                                            style = MaterialTheme.typography.headlineMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = feicheiel.technologies.trackme.ui.theme.SourceSansProFontFamily
                                            ),
                                            color = Color(0xFF89C9F7)
                                        )
                                    }

                                    Text(
                                        text = "Past $uniqueDays days | Travelled",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                                            fontFamily = feicheiel.technologies.trackme.ui.theme.SourceSansProFontFamily
                                        )
                                    )
                                }
                            }

                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                            if (isSyncing) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Syncing...", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (isPaused) "Tracking Paused" else "Pause Logging", style = MaterialTheme.typography.bodyMedium)
                                    if (!isPaused) {
                                        Box {
                                            Text(
                                                text = "Duration: $selectedPauseDuration",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFFFA726),//MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .pointerInput(Unit) {
                                                        detectTapGestures(onTap = { isPauseMenuExpanded = true })
                                                    }
                                                    .padding(vertical = 4.dp)
                                            )
                                            DropdownMenu(
                                                expanded = isPauseMenuExpanded,
                                                onDismissRequest = { isPauseMenuExpanded = false }
                                            ) {
                                                listOf("1 min", "30 min", "1 hour").forEach { duration ->
                                                    DropdownMenuItem(
                                                        text = { Text(duration) },
                                                        onClick = {
                                                            selectedPauseDuration = duration
                                                            isPauseMenuExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Button(
                                    onClick = {
                                        val durationMs = when (selectedPauseDuration) {
                                            "1 min" -> 60000L
                                            "30 min" -> 1800000L
                                            else -> 3600000L
                                        }
                                        val intent = Intent(context, ForeGroundService::class.java).apply {
                                            action = if (isPaused) ForeGroundService.Actions.RESUME.toString() else ForeGroundService.Actions.PAUSE.toString()
                                            if (!isPaused) {
                                                putExtra("duration_ms", durationMs)
                                                putExtra("duration_text", selectedPauseDuration)
                                            }
                                        }
                                        context.startService(intent)
                                    },
                                    shape = RoundedCornerShape(17.dp),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer,
                                        contentColor = if (isPaused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text(if (isPaused) "Resume Now" else "Pause for $selectedPauseDuration",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = feicheiel.technologies.trackme.ui.theme.SourceSansProFontFamily
                                        )
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Draw Tracks", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = isDrawTracksEnabled,
                                    onCheckedChange = { isDrawTracksEnabled = it }
                                )
                            }

                            if (isDrawTracksEnabled) {
                                Box {
                                    Button(
                                        onClick = { isDurationMenuExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    ) {
                                        Icon(Icons.Rounded.History, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Duration: $selectedDuration",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Light,
                                                fontFamily = feicheiel.technologies.trackme.ui.theme.SourceSansProFontFamily
                                            )
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = isDurationMenuExpanded,
                                        onDismissRequest = { isDurationMenuExpanded = false }
                                    ) {
                                        listOf("Last Hour", "Last 24h", "All").forEach { duration ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = duration,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontWeight = FontWeight.Light,
                                                            fontFamily = feicheiel.technologies.trackme.ui.theme.SourceSansProFontFamily
                                                        )
                                                    )
                                                },
                                                onClick = {
                                                    selectedDuration = duration
                                                    isDurationMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun importCsvFromUri(context: Context, uri: Uri, currentUserId: String): Int {
    return try {
        val database = AppDatabase.getDatabase(context)
        val existingPoints = database.locationDao().getAllPoints(currentUserId).toMutableList()
        val existingTimestamps = existingPoints.map { it.timestamp }.toSet()
        val importedPoints = mutableListOf<LocationEntity>()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = inputStream.bufferedReader()
            val _header = reader.readLine() // Skip header
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            reader.forEachLine { line ->
                val parts = line.split(",")
                if (parts.size >= 10) {
                    try {
                        val lat = parts[2].toDouble()
                        val lon = parts[3].toDouble()
                        val tsText = parts[4]
                        val timestamp = try {
                            sdf.parse(tsText)?.time ?: tsText.toLong()
                        } catch (e: Exception) {
                            tsText.toLong()
                        }

                        if (!existingTimestamps.contains(timestamp)) {
                            importedPoints.add(LocationEntity(
                                userId = currentUserId,
                                latitude = lat,
                                longitude = lon,
                                timestamp = timestamp,
                                speed = parts[5].toFloatOrNull(),
                                accuracy = parts[6].toFloatOrNull() ?: 0f,
                                isSynced = false
                            ))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        if (importedPoints.isNotEmpty()) {
            // Sort merged list by timestamp to ensure sequence for distance calculation
            val allMerged = (existingPoints + importedPoints).sortedBy { it.timestamp }

            val updatedPoints = mutableListOf<LocationEntity>()
            var totalDist = 0f
            var lastPoint: LocationEntity? = null

            allMerged.forEach { point ->
                var distFromPrev = 0f
                if (lastPoint != null) {
                    val result = FloatArray(1)
                    android.location.Location.distanceBetween(
                        lastPoint.latitude, lastPoint.longitude,
                        point.latitude, point.longitude,
                        result
                    )
                    distFromPrev = result[0]
                }
                totalDist += distFromPrev
                updatedPoints.add(point.copy(
                    id = 0, // Reset ID to let Room autogenerate after deleteAll
                    distanceFromPrevious = distFromPrev,
                    totalDistance = totalDist
                ))
                lastPoint = updatedPoints.last()
            }

            database.locationDao().deleteAll(currentUserId)
            database.locationDao().insertAll(updatedPoints)
        }
        importedPoints.size
    } catch (e: Exception) {
        e.printStackTrace()
        -1
    }
}

suspend fun writeCsvToUri(context: Context, uri: Uri, points: List<LocationEntity>): Boolean {
    return try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            val csvHeader = "ID,UserID,Latitude,Longitude,Timestamp,Speed,Accuracy,DistancePrev,TotalDistance,IsSynced\n"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            outputStream.write(csvHeader.toByteArray())
            points.forEach { point ->
                val line = "${point.id},${point.userId},${point.latitude},${point.longitude},${sdf.format(Date(point.timestamp))},${point.speed ?: 0f},${point.accuracy},${point.distanceFromPrevious},${point.totalDistance},${point.isSynced}\n"
                outputStream.write(line.toByteArray())
            }
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

@Composable
fun LocationGlowIndicator(status: ForeGroundService.Status) {
    val color = when (status) {
        ForeGroundService.Status.ACTIVE -> Color(0xFF00FF00)
        ForeGroundService.Status.SEARCHING -> PROJECT_Yellow
        ForeGroundService.Status.ERROR -> PROJECT_Red
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    // Safety: Use a slightly more robust animation setup
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 2.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            // Safety check for NaN or infinite values which cause system_server frame errors
            if (minDim > 0 && !pulseScale.isNaN() && !pulseAlpha.isNaN()) {
                drawCircle(
                    color = color,
                    radius = (minDim / 4) * pulseScale.coerceAtLeast(0.1f),
                    alpha = pulseAlpha.coerceIn(0f, 1f),
                    style = Fill
                )
            }
        }

        Surface(
            modifier = Modifier.size(14.dp),
            shape = CircleShape,
            color = color,
            shadowElevation = 6.dp,
            border = BorderStroke(2.dp, Color.White)
        ) {}
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    LocationGlowIndicator(ForeGroundService.Status.ACTIVE)
}

