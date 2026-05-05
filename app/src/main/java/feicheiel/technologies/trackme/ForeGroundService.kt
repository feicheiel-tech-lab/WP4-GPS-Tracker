package feicheiel.technologies.trackme

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import androidx.activity.result.launch
import java.util.Locale
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.PowerManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ForeGroundService: Service() {

    private val NOTIFICATION_ID = 3073
    private lateinit var remoteViews: RemoteViews
    private lateinit var notificationManager: NotificationManager
    private lateinit var builder: NotificationCompat.Builder
    private var wakeLock: PowerManager.WakeLock? = null

    // Dedicated background thread for GPS callbacks.
    // Registering the LocationCallback on this Looper instead of Looper.getMainLooper()
    // means every onLocationResult() invocation — spike filtering, Kalman, DB writes —
    // runs entirely off the UI thread, keeping the main thread free for rendering.
    private val locationHandlerThread = android.os.HandlerThread("LocationCallbackThread").also { it.start() }
    private val locationLooper get() = locationHandlerThread.looper

    private var isServiceStarted = false
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Spike Filter: per-point rejection against the last accepted point.
    // A point is rejected if its implied speed exceeds MAX_SPEED_MS *and* its
    // accuracy is worse than MIN_ACCURACY_M (weak GPS signal + large jump = spike).
    // After MAX_CONSECUTIVE_REJECTIONS in a row we accept anyway — the user may
    // have genuinely moved fast or the GPS recovered to a new position.
    private val MAX_SPEED_MS = 55f          // ~200 km/h — anything faster is a spike
    private val MIN_ACCURACY_M = 30f        // only reject when accuracy is also poor
    private val MAX_CONSECUTIVE_REJECTIONS = 5
    private var lastAcceptedLocation: Location? = null
    private var consecutiveRejections = 0

    // Kalman Filter
    private var kalmanLat: KalmanFilter? = null
    private var kalmanLon: KalmanFilter? = null

    private var trackWhenNotMoving = false

    companion object {
        //This allows MainActivity to Observe the location without its own client
        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation = _currentLocation.asStateFlow()

        private val _currentStatus = MutableStateFlow<Status>(Status.SEARCHING)
        val currentStatus = _currentStatus.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
    }

    //only updates the notification if the city name is different
    private var cityName: String = "Locating..."
        set(value) {
            if (field == value) return
            field = value
            updateNotificationUI()
        }

    private var status: Status = Status.SEARCHING
        set(value){
            if (field == value) return
            field = value
            _currentStatus.value = value // Sync with MainActivity
            updateStatusUI()
        }

    //Database files
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentUserId: String? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        remoteViews = RemoteViews(packageName, R.layout.lyt_notification_mine)

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { freshLocation ->
                    val now = System.currentTimeMillis()

                    // 1. Spike filter: reject GPS jumps that are physically implausible.
                    //    We compare the incoming point against the last *accepted* point
                    //    using both implied speed and the GPS accuracy report.
                    val isSpike = isSpikeReading(freshLocation)
                    if (isSpike) {
                        consecutiveRejections++
                        if (consecutiveRejections < MAX_CONSECUTIVE_REJECTIONS) {
                            // Still within the rejection cap — discard this reading entirely.
                            // We still update the UI status so the indicator stays live.
                            status = Status.ACTIVE
                            return@let
                        }
                        // Rejection cap reached: accept despite the spike so the track
                        // doesn't freeze if the user genuinely relocated (e.g. tunnel exit).
                    }
                    // Point accepted — reset the rejection counter.
                    consecutiveRejections = 0
                    lastAcceptedLocation = freshLocation

                    // 2. Apply Kalman Filter for smoothing accepted points.
                    if (kalmanLat == null) {
                        kalmanLat = KalmanFilter(3f)
                        kalmanLon = KalmanFilter(3f)
                    }

                    val accuracy = if (freshLocation.hasAccuracy()) freshLocation.accuracy else 25f
                    val kLat = kalmanLat!!.filter(freshLocation.latitude, accuracy, freshLocation.time)
                    val kLon = kalmanLon!!.filter(freshLocation.longitude, accuracy, freshLocation.time)

                    val filteredLocation = Location(freshLocation).apply {
                        latitude = kLat
                        longitude = kLon
                    }

                    _currentLocation.value = filteredLocation
                    status = Status.ACTIVE

                    // Throttle geocoder to once every 30 seconds to save resources.
                    if (now - lastGeocoderTime > 30000L) {
                        lastGeocoderTime = now
                        updateCityFromLocation(filteredLocation.latitude, filteredLocation.longitude)
                    }

                    // 3. Check movement and save.
                    val isMoving = if (filteredLocation.hasSpeed()) filteredLocation.speed > 0.6f else true

                    if ((isMoving || trackWhenNotMoving) && currentUserId != null) {
                        serviceScope.launch {
                            val lastPoint = database.locationDao().getLastPoint(currentUserId!!)
                            var distanceToPrev = 0f
                            var newTotalDistance = 0f

                            if (lastPoint != null) {
                                val result = FloatArray(1)
                                Location.distanceBetween(
                                    lastPoint.latitude, lastPoint.longitude,
                                    filteredLocation.latitude, filteredLocation.longitude,
                                    result
                                )
                                distanceToPrev = result[0]
                                newTotalDistance = lastPoint.totalDistance + distanceToPrev
                            }

                            val entity = LocationEntity(
                                userId = currentUserId!!,
                                latitude = filteredLocation.latitude,
                                longitude = filteredLocation.longitude,
                                timestamp = filteredLocation.time,
                                speed = if (filteredLocation.hasSpeed()) filteredLocation.speed else 0.0f,
                                accuracy = filteredLocation.accuracy,
                                distanceFromPrevious = distanceToPrev,
                                totalDistance = newTotalDistance,
                                isSynced = false
                            )
                            database.locationDao().insert(entity)

                            // Update statistics in notification.
                            val allPoints = database.locationDao().getAllPoints(currentUserId!!)
                            updateStatsUI(newTotalDistance, allPoints.size)
                        }
                    }
                }
            }
        }
        registerReceiver(providerReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))

        // Database Initialization
        database = AppDatabase.getDatabase(this)
        // Retrieve current user ID from SharedPreferences - Default to "default_user"
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        currentUserId = prefs.getString("current_user_id", "default_user") ?: "default_user"
        trackWhenNotMoving = prefs.getBoolean("track_when_not_moving", false)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(providerReceiver)
        stopLocationUpdates()
        releaseWakeLock()
        serviceScope.cancel()
        locationHandlerThread.quitSafely() // release the background thread cleanly
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Default to START if intent is null
        val action = intent?.action ?: Actions.START.toString()

        when(action){
            Actions.START.toString() -> {
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                trackWhenNotMoving = prefs.getBoolean("track_when_not_moving", false)
                start() // Ensures notification is shown/refreshed
                if (!isServiceStarted) {
                    startLocationUpdates()
                    isServiceStarted = true
                    _isRunning.value = true
                }
            }
            Actions.STOP.toString() -> {
                stopLocationUpdates()
                stopSelf()
                isServiceStarted = false
                _isRunning.value = false
            }
            Actions.SYNC.toString() -> {
                syncData()
            }
            "UPDATE_SETTINGS" -> {
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                trackWhenNotMoving = prefs.getBoolean("track_when_not_moving", false)
            }
            else -> {
                //Handle system restart (START_STICKY)
                if (intent == null && !isServiceStarted) {
                    val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    trackWhenNotMoving = prefs.getBoolean("track_when_not_moving", false)
                    start()
                    startLocationUpdates()
                    isServiceStarted = true
                    _isRunning.value = true
                }
            }
        }
        //return super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(){
        acquireWakeLock()
        // Faster interval (1s) to help get GPS fix when offline
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(1000).build()
        fusedLocationProviderClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            locationLooper  // GPS callbacks run on background thread, not the UI thread
        )
    }

    private fun stopLocationUpdates(){
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackMe::LocationWakeLock")
            wakeLock?.acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private var lastGeocoderTime = 0L

    private fun updateCityFromLocation(lat: Double, lon: Double){
        serviceScope.launch {
            try {
                val geocoder = Geocoder(this@ForeGroundService, Locale.getDefault())
                // Use the newer API if available or wrap in try-catch for older
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    cityName = addresses[0].locality ?: addresses[0].adminArea ?: "Unknown"
                }
            } catch (e: Exception) {
                Log.e("ForeGroundService", "Geocoder error: ${e.message}")
            }
        }
    }

    enum class Actions {
        START, STOP, UPDATE, SYNC
    }

    enum class Status {
        ACTIVE, SEARCHING, ERROR
    }

    private fun start(){
        //Intent that Opens MainActivity when notification is clicked
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val relaunchIntent = Intent(this, ForeGroundService::class.java).apply {
            action = Actions.START.toString()
        }
        val deletePendingIntent = PendingIntent.getService(this, 0, relaunchIntent, PendingIntent.FLAG_IMMUTABLE)

        builder = NotificationCompat.Builder(this, "running_channel")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        updateStatusUI() // Ensure initial status (Searching/Orange) is applied
        updateNotificationUI()
        startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun updateNotificationUI() {
        // Ensure the builder and views are initialized before updating
        if (!::remoteViews.isInitialized || !::builder.isInitialized) return

        val text = if (status == Status.ERROR) {
            "Tracking PAUSED: GPS is turned off!"
        } else {
            getString(R.string.notification_text, cityName)
        }

        // Update the text using the string resource placeholder
        remoteViews.setTextViewText(R.id.txt_ntf_text, text)

        // Push the update to the notification shade
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun updateStatusUI() {
        if (!::remoteViews.isInitialized || !::builder.isInitialized) return

        val drawable = when (status) {
            Status.ACTIVE -> R.drawable.notification_indicator_green
            Status.SEARCHING -> R.drawable.notification_indicator_orange
            Status.ERROR -> R.drawable.notification_indicator_red
        }

        remoteViews.setImageViewResource(R.id.notification_statusDot, drawable)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun updateStatsUI(totalDistance: Float, pointsCount: Int) {
        if (!::remoteViews.isInitialized || !::builder.isInitialized) return

        val distanceKm = totalDistance / 1000f
        val statsText = String.format("%.2f km | %d pts", distanceKm, pointsCount)

        remoteViews.setTextViewText(R.id.txt_ntf_stats, statsText)

        // Check for unsynced points to show sync button
        serviceScope.launch {
            val unsyncedCount = if (currentUserId != null) {
                database.locationDao().getUnsyncedPoints(currentUserId!!).size
            } else 0

            if (unsyncedCount > 0) {
                val syncIntent = Intent(this@ForeGroundService, ForeGroundService::class.java).apply {
                    action = Actions.SYNC.toString()
                }
                val syncPendingIntent = PendingIntent.getService(
                    this@ForeGroundService,
                    1,
                    syncIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                remoteViews.setViewVisibility(R.id.btn_ntf_sync, android.view.View.VISIBLE)
                remoteViews.setOnClickPendingIntent(R.id.btn_ntf_sync, syncPendingIntent)
            } else {
                remoteViews.setViewVisibility(R.id.btn_ntf_sync, android.view.View.GONE)
            }
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun syncData() {
        if (currentUserId == null) return
        val intent = Intent(this, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_START_SYNC
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION){
                checkLocationSettings()
            }
        }
    }

    private fun checkLocationSettings(){
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        if (!isGpsEnabled){
            status = Status.ERROR
            cityName = "GPS Disabled"
        } else {
            if (status == Status.ERROR) {
                status = Status.SEARCHING
                cityName = "Locating..."
                startLocationUpdates()
            }
        }
    }

    /**
     * Returns true if [incoming] is a GPS spike and should be rejected.
     *
     * A reading is considered a spike when ALL of the following are true:
     *   1. We have a previous accepted point to compare against.
     *   2. The implied speed from the last accepted point to this one exceeds
     *      MAX_SPEED_MS (~200 km/h) — physically implausible for a pedestrian
     *      or normal vehicle track.
     *   3. The GPS accuracy is worse than MIN_ACCURACY_M (30 m) — a confident
     *      GPS fix that reports high speed is likely real (e.g. a fast vehicle),
     *      so we only reject when the hardware itself signals uncertainty.
     *
     * If MAX_CONSECUTIVE_REJECTIONS is reached the caller accepts anyway to
     * prevent the track from freezing after a tunnel, building, or signal loss.
     */
    private fun isSpikeReading(incoming: Location): Boolean {
        val prev = lastAcceptedLocation ?: return false  // no baseline yet — always accept

        val timeDeltaSeconds = (incoming.time - prev.time) / 1000.0
        if (timeDeltaSeconds <= 0) return false          // clock anomaly — accept safely

        // Compute straight-line distance between previous accepted point and this one.
        val distanceResult = FloatArray(1)
        Location.distanceBetween(
            prev.latitude, prev.longitude,
            incoming.latitude, incoming.longitude,
            distanceResult
        )
        val distanceMetres = distanceResult[0]

        // Implied speed in m/s.
        val impliedSpeed = distanceMetres / timeDeltaSeconds

        // Reject only when speed is implausible AND the GPS itself is uncertain.
        val accuracyPoor = !incoming.hasAccuracy() || incoming.accuracy > MIN_ACCURACY_M
        return impliedSpeed > MAX_SPEED_MS && accuracyPoor
    }

    /**
     * A simple 1D Kalman Filter for location smoothing.
     */
    private class KalmanFilter(private val processNoise: Float) {
        private var x = 0.0 // State estimate
        private var p = 0.0 // Error covariance
        private var lastTimestamp = 0L

        fun filter(measurement: Double, accuracy: Float, timestamp: Long): Double {
            if (lastTimestamp == 0L) {
                x = measurement
                p = (accuracy * accuracy).toDouble()
                lastTimestamp = timestamp
                return x
            }

            val deltaTime = (timestamp - lastTimestamp) / 1000.0
            if (deltaTime > 0) {
                // Prediction: increase covariance over time
                p += deltaTime * processNoise * processNoise
                lastTimestamp = timestamp
            }

            // Kalman Gain
            val r = (accuracy * accuracy).toDouble()
            val k = p / (p + r)

            // Update
            x += k * (measurement - x)
            p *= (1 - k)

            return x
        }
    }
}