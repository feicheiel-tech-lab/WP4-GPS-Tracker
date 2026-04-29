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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.PowerManager
import kotlinx.coroutines.launch

class ForeGroundService: Service() {

    private val NOTIFICATION_ID = 3073
    private lateinit var remoteViews: RemoteViews
    private lateinit var notificationManager: NotificationManager
    private lateinit var builder: NotificationCompat.Builder
    private var wakeLock: PowerManager.WakeLock? = null

    private var isServiceStarted = false
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Median Filter Window: 30 seconds
    private val windowSizeMillis = 30000L
    private val locationHistory = mutableListOf<Location>()

    // Kalman Filter
    private var kalmanLat: KalmanFilter? = null
    private var kalmanLon: KalmanFilter? = null

    companion object {
        //This allows MainActivity to Observe the location without its own client
        private val _currentLocation = MutableStateFlow<Location?>(null)
        val currentLocation = _currentLocation.asStateFlow()

        private val _currentStatus = MutableStateFlow<Status>(Status.SEARCHING)
        val currentStatus = _currentStatus.asStateFlow()
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
                locationResult.lastLocation?.let{ freshLocation ->
                    // 1. Maintain the time window
                    val now = System.currentTimeMillis()
                    locationHistory.add(freshLocation)
                    locationHistory.removeAll { now - it.time > windowSizeMillis }

                    // 2. Compute Median if we have enough points
                    var filteredLocation = if (locationHistory.size >= 3) {
                        applyMedianFilter(locationHistory)
                    } else {
                        freshLocation
                    }

                    // 3. Apply Kalman Filter for additional smoothing
                    if (kalmanLat == null) {
                        kalmanLat = KalmanFilter(3f) // 3m process noise
                        kalmanLon = KalmanFilter(3f)
                    }
                    
                    val accuracy = if (filteredLocation.hasAccuracy()) filteredLocation.accuracy else 25f
                    val kLat = kalmanLat!!.filter(filteredLocation.latitude, accuracy, filteredLocation.time)
                    val kLon = kalmanLon!!.filter(filteredLocation.longitude, accuracy, filteredLocation.time)

                    filteredLocation = Location(filteredLocation).apply {
                        latitude = kLat
                        longitude = kLon
                    }

                    _currentLocation.value = filteredLocation
                    status = Status.ACTIVE
                    updateCityFromLocation(filteredLocation.latitude, filteredLocation.longitude)

                    // 4. Check movement and save
                    val isMoving = if (filteredLocation.hasSpeed()) filteredLocation.speed > 0.6f else true
                    
                    if (isMoving && currentUserId != null) {
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
                            
                            // Update statistics in notification
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
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(providerReceiver)
        stopLocationUpdates()
        releaseWakeLock()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Default to START if intent is null
        val action = intent?.action ?: Actions.START.toString()

        when(action){
            Actions.START.toString() -> {
                start() // Ensures notification is shown/refreshed
                if (!isServiceStarted) {
                    startLocationUpdates()
                    isServiceStarted = true
                }
            }
            Actions.STOP.toString() -> {
                stopLocationUpdates()
                stopSelf()
                isServiceStarted = false
            }
            else -> {
                //Handle system restart (START_STICKY)
                if (intent == null && !isServiceStarted) {
                    start()
                    startLocationUpdates()
                    isServiceStarted = true
                }
            }
        }
        //return super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(){
        acquireWakeLock()
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(3300).build()
        fusedLocationProviderClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
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

    private fun updateCityFromLocation(lat: Double, lon: Double){
        try {
            val geocoder = Geocoder(this@ForeGroundService, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                cityName = addresses[0].locality ?: addresses[0].adminArea ?: "Unknown"
            }
        } catch (e: Exception) {
            Log.e("ForeGroundService", "Geocoder error: ${e.message}")
        }
    }

    enum class Actions {
        START, STOP, UPDATE
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
        notificationManager.notify(NOTIFICATION_ID, builder.build())
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

    private fun applyMedianFilter(history: List<Location>): Location {
        val sortedLat = history.map { it.latitude }.sorted()
        val sortedLon = history.map { it.longitude }.sorted()
        
        val medianLat = sortedLat[sortedLat.size / 2]
        val medianLon = sortedLon[sortedLon.size / 2]
        
        // Return a copy of the latest location with median coords
        return Location(history.last()).apply {
            latitude = medianLat
            longitude = medianLon
        }
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