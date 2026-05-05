package feicheiel.technologies.trackme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import feicheiel.technologies.trackme.api.GeoPointRequest
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    companion object {
        const val CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID = 2
        const val ACTION_START_SYNC = "START_SYNC"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SYNC) {
            startForeground(NOTIFICATION_ID, createNotification("Preparing sync...", 0, 100, ""))
            startSync()
        }
        return START_NOT_STICKY
    }

    private fun startSync() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val database = AppDatabase.getDatabase(applicationContext)
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val userId = prefs.getString("current_user_id", null)
                var authToken = prefs.getString("auth_token", "") ?: ""

                if (userId == null) {
                    stopSelf()
                    return@launch
                }

                val unsyncedPoints = database.locationDao().getUnsyncedPoints(userId)
                if (unsyncedPoints.isEmpty()) {
                    updateNotification("Nothing to sync", 100, 100, "")
                    delay(2000)
                    stopSelf()
                    return@launch
                }

                val total = unsyncedPoints.size
                val batchSize = 100
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val apiService = (applicationContext as TrackMeApp).apiService

                for (i in 0 until total step batchSize) {
                    val end = (i + batchSize).coerceAtMost(total)
                    val batch = unsyncedPoints.subList(i, end)
                    
                    val progress = (i * 100) / total
                    
                    // Estimate time left
                    val elapsed = System.currentTimeMillis() - startTime
                    val timeLeftStr = if (i > 0) {
                        val estimatedTotalTime = (elapsed.toDouble() / i) * total
                        val remainingMs = (estimatedTotalTime - elapsed).toLong().coerceAtLeast(0L)
                        val remainingSec = (remainingMs / 1000) % 60
                        val remainingMin = (remainingMs / (1000 * 60))
                        String.format(Locale.US, "~%dm %ds left", remainingMin, remainingSec)
                    } else {
                        "Calculating..."
                    }

                    updateNotification("Syncing: $i/$total", progress, 100, timeLeftStr)

                    val geoRequests = batch.map {
                        GeoPointRequest(
                            device = userId,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            timestamp = sdf.format(Date(it.timestamp))
                        )
                    }

                    var response = apiService.uploadPoints("Bearer $authToken", geoRequests)
                    
                    if (response.code() == 401) {
                        Log.d("SyncService", "Token expired, attempting refresh...")
                        val refreshToken = prefs.getString("refresh_token", "") ?: ""
                        if (refreshToken.isNotEmpty()) {
                            val refreshResponse = apiService.refreshToken(feicheiel.technologies.trackme.api.RefreshRequest(refreshToken))
                            if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                                val newAccessToken = refreshResponse.body()!!.access
                                prefs.edit().putString("auth_token", newAccessToken).apply()
                                authToken = newAccessToken
                                Log.d("SyncService", "Token refreshed successfully, retrying batch...")
                                response = apiService.uploadPoints("Bearer $authToken", geoRequests)
                            } else {
                                Log.e("SyncService", "Refresh token failed or expired")
                            }
                        }
                    }

                    if (response.isSuccessful) {
                        val syncedBatch = batch.map { it.copy(isSynced = true) }
                        database.locationDao().markAsSynced(syncedBatch)
                    } else {
                        Log.e("SyncService", "Batch upload failed: ${response.code()}")
                    }
                }

                updateNotification("Sync complete!", 100, 100, "")
                delay(2000)
                stopSelf()

            } catch (e: Exception) {
                Log.e("SyncService", "Sync error: ${e.message}")
                updateNotification("Sync failed", 0, 100, "")
                delay(3000)
                stopSelf()
            }
        }
    }

    private fun createNotification(content: String, progress: Int, max: Int, subText: String = ""): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Data Sync")
            .setContentText(content)
            .setSubText(subText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(max, progress, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String, progress: Int, max: Int, subText: String = "") {
        val notification = createNotification(content, progress, max, subText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        syncJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
