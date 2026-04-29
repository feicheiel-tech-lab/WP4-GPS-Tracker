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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import feicheiel.technologies.trackme.ui.theme.PROJECT_Red
import feicheiel.technologies.trackme.ui.theme.PROJECT_Yellow
import feicheiel.technologies.trackme.ui.theme.TrackMeTheme
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

import java.io.File
import java.io.FileOutputStream
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.views.overlay.CopyrightOverlay
import androidx.compose.ui.platform.ComposeView

import androidx.compose.material.icons.rounded.FileDownload
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.IconButton
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.material3.HorizontalDivider

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            startTrackingService()
            requestIgnoreBatteryOptimizations()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        copyOfflineMapFromAssets("map.sqlite")

        setContent {
            TrackMeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OSMMapScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchPermissionRequest() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
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
fun OSMMapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    
    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    val userId = prefs.getString("current_user_id", "default_user") ?: "default_user"

    val location by ForeGroundService.currentLocation.collectAsState()
    val status by ForeGroundService.currentStatus.collectAsState()
    val syncedCount by database.locationDao().getSyncedCountFlow(userId).collectAsState(initial = 0)
    val unsyncedCount by database.locationDao().getUnsyncedCountFlow(userId).collectAsState(initial = 0)
    val allPoints by database.locationDao().getAllPointsFlow(userId).collectAsState(initial = emptyList())

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

    var isDrawTracksEnabled by remember { mutableStateOf(false) }
    var selectedDuration by remember { mutableStateOf("All") }
    var isDurationMenuExpanded by remember { mutableStateOf(false) }
    var isFollowingUser by remember { mutableStateOf(true) }
    
    var isPanelExpanded by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val screenHeight = configuration.screenHeightDp.dp
    val expandedHeight = screenHeight * 0.35f
    val collapsedHeight = 35.dp // Very compact collapsed state
    
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

    // Use CartoDB Dark Matter tiles - highly reliable and clear for dark theme
    val darkTileSource = remember {
        XYTileSource(
            "CartoDark",
            1, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/",
                "https://d.basemaps.cartocdn.com/dark_all/"
            )
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(darkTileSource)
                    setMultiTouchControls(true)
                    setUseDataConnection(true)
                    controller.setZoom(16.0)
                    mapViewInstance.value = this

                    clipChildren = false
                    clipToPadding = false

                    val rotationGestureOverlay = RotationGestureOverlay(this)
                    rotationGestureOverlay.isEnabled = true
                    overlays.add(rotationGestureOverlay)

                    val copyrightOverlay = CopyrightOverlay(ctx).apply {
                        setCopyrightNotice("© CartoDB, © OpenStreetMap contributors")
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
                    } else {
                        val lp = indicatorComposeView.layoutParams as MapView.LayoutParams
                        lp.geoPoint = userPoint
                        view.updateViewLayout(indicatorComposeView, lp)
                    }

                    if (isFollowingUser){
                        view.controller.animateTo(userPoint)
                    }
                }

                view.overlays.removeAll { it is Polyline }
                if (isDrawTracksEnabled) {
                    val now = System.currentTimeMillis()
                    val filteredPoints = allPoints.filter { 
                        when(selectedDuration) {
                            "Last Hour" -> it.timestamp > now - 3600000
                            "Last 24h" -> it.timestamp > now - 86400000
                            else -> true
                        }
                    }
                    if (filteredPoints.isNotEmpty()) {
                        val polyline = Polyline().apply {
                            setPoints(filteredPoints.map { GeoPoint(it.latitude, it.longitude) })
                            outlinePaint.color = android.graphics.Color.CYAN
                            outlinePaint.strokeWidth = 10f
                        }
                        view.overlays.add(polyline)
                    }
                }
                view.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // SYNC STATUS & BUTTON OVERLAY
        val syncColor = if (unsyncedCount > 0) Color(0xFFFFD355) else Color(0xFF509FB7)
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 20.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(24.dp),
                    spotColor = syncColor,
                    ambientColor = syncColor
                )
                .border(1.5.dp, syncColor.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
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
                            val points = database.locationDao().getUnsyncedPoints(userId)
                            database.locationDao().markAsSynced(points.map { it.copy(isSynced = true) })
                        }
                    }
                ) {
                    Icon(
                        Icons.Rounded.CloudSync, 
                        contentDescription = "Sync",
                        tint = MaterialTheme.colorScheme.primary
                    )
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
                mapViewInstance.value?.let { 
                    it.mapOrientation = 0f
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = fabPadding, end = 20.dp),
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
                    elevation = 24.dp,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    spotColor = Color(0xFF00008B),
                    ambientColor = Color(0xFF00008B)
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Synced: $syncedCount", style = MaterialTheme.typography.titleMedium)
                                Text("Unsynced: $unsyncedCount", 
                                    style = MaterialTheme.typography.titleMedium, 
                                    color = if(unsyncedCount > 0) PROJECT_Red else MaterialTheme.colorScheme.onSurface)
                                Text("Total Points: ${allPoints.size}", style = MaterialTheme.typography.labelMedium)
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                val lastPoint = allPoints.lastOrNull()
                                val dist = lastPoint?.totalDistance?.div(1000) ?: 0f
                                Text(
                                    text = String.format("%.2f km", dist),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("Travelled", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val success = exportDatabaseToCsv(context, allPoints)
                                        if (success) Toast.makeText(context, "CSV exported", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.FileDownload, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Export")
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Tracks", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                Switch(checked = isDrawTracksEnabled, onCheckedChange = { isDrawTracksEnabled = it })
                            }
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
                                    Text("Duration: $selectedDuration")
                                }
                                DropdownMenu(
                                    expanded = isDurationMenuExpanded,
                                    onDismissRequest = { isDurationMenuExpanded = false }
                                ) {
                                    listOf("Last Hour", "Last 24h", "All").forEach { duration ->
                                        DropdownMenuItem(
                                            text = { Text(duration) },
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

suspend fun exportDatabaseToCsv(context: Context, points: List<LocationEntity>): Boolean {
    if (points.isEmpty()) return false
    
    val fileName = "location_export_${System.currentTimeMillis()}.csv"
    val csvHeader = "ID,UserID,Latitude,Longitude,Timestamp,Speed,Accuracy,DistancePrev,TotalDistance,IsSynced\n"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    val csvData = StringBuilder()
    csvData.append(csvHeader)
    
    points.forEach { point ->
        csvData.append("${point.id},")
        csvData.append("${point.userId},")
        csvData.append("${point.latitude},")
        csvData.append("${point.longitude},")
        csvData.append("${sdf.format(Date(point.timestamp))},")
        csvData.append("${point.speed ?: 0f},")
        csvData.append("${point.accuracy},")
        csvData.append("${point.distanceFromPrevious},")
        csvData.append("${point.totalDistance},")
        csvData.append("${point.isSynced}\n")
    }
    
    return try {
        val file = File(context.getExternalFilesDir(null), fileName)
        file.writeText(csvData.toString())
        
        // Use Intent to share or view the file
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share CSV Export"))
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
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 2.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = color,
                radius = (size.minDimension / 4) * pulseScale,
                alpha = pulseAlpha,
                style = Fill
            )
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