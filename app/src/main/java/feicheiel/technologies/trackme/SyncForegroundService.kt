package feicheiel.technologies.trackme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import feicheiel.technologies.trackme.api.ApiService
import feicheiel.technologies.trackme.api.DownloadRequest
import feicheiel.technologies.trackme.api.GeoPointRequest
import feicheiel.technologies.trackme.api.RefreshRequest
import feicheiel.technologies.trackme.api.TokenRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    companion object {
        const val CHANNEL_ID      = "sync_channel"
        const val NOTIFICATION_ID = 2
        const val ACTION_UPLOAD   = "ACTION_UPLOAD"
        const val ACTION_DOWNLOAD = "ACTION_DOWNLOAD"

        // Fixed credentials — stored only here, never in SharedPreferences.
        private const val API_USERNAME = "William"
        private const val API_PASSWORD = "MovingImpact2026"

        // ISO-8601 UTC formatter shared across the service.
        val isoSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Observed by MainActivity to drive the sync spinner in the UI.
        private val _isSyncing = kotlinx.coroutines.flow.MutableStateFlow(false)
        val isSyncing = _isSyncing.asStateFlow()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = if (Constants.IS_TEST) "${getDeviceID()} [test-data]" else getDeviceID()

        when (intent?.action) {
            ACTION_UPLOAD -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparing upload…", 0, 0))
                syncJob?.cancel()
                _isSyncing.value = true
                syncJob = serviceScope.launch {
                    try { runUpload(deviceId) } finally { _isSyncing.value = false }
                }
            }
            ACTION_DOWNLOAD -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", 0, 0))
                syncJob?.cancel()
                _isSyncing.value = true
                syncJob = serviceScope.launch {
                    try { runDownload(deviceId) } finally { _isSyncing.value = false }
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        syncJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Token helpers ─────────────────────────────────────────────────────────

    /**
     * Gets a fresh access token for each sync session.
     *
     * Strategy (in order):
     *   1. If a refresh token is stored, use it — this avoids a full credential
     *      login and keeps the session alive long-term.
     *      IMPORTANT: SimpleJWT rotates refresh tokens on every use, so we always
     *      save the NEW refresh token returned alongside the new access token.
     *   2. If no refresh token is stored, or if the refresh call fails (token
     *      expired / revoked), fall back to a full username+password login and
     *      save both the new access and refresh tokens.
     *
     * We never cache or re-use the access token between syncs — its TTL is short
     * (typically 5 minutes on SimpleJWT) and we can't know if it's still valid
     * without making a network call anyway.
     */
    private suspend fun getValidAccessToken(api: ApiService): String? {
        val prefs = getSharedPreferences("api_tokens", Context.MODE_PRIVATE)
        val storedRefresh = prefs.getString("refresh_token", null)

        // Prefer refresh token path — avoids sending credentials on every sync.
        if (storedRefresh != null) {
            Log.d("SyncService", "Refreshing access token…")
            val resp = api.refreshToken(RefreshRequest(storedRefresh))
            if (resp.isSuccessful && resp.body() != null) {
                val newAccess  = resp.body()!!.access
                // SimpleJWT rotates the refresh token on every use — always save
                // the new one or the next sync will fail with a 401.
                val newRefresh = resp.body()!!.refresh
                prefs.edit()
                    .putString("access_token",  newAccess)
                    .putString("refresh_token", newRefresh)
                    .apply()
                Log.d("SyncService", "Token refreshed successfully.")
                return newAccess
            }
            // Refresh token is expired or revoked — fall through to full login.
            Log.w("SyncService", "Refresh token rejected — falling back to full login…")
            prefs.edit().remove("access_token").remove("refresh_token").apply()
        }

        // Full login with credentials.
        Log.d("SyncService", "No refresh token — authenticating with credentials…")
        return try {
            val resp = api.getToken(TokenRequest(API_USERNAME, API_PASSWORD))
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                prefs.edit()
                    .putString("access_token",  body.access)
                    .putString("refresh_token", body.refresh)
                    .apply()
                Log.d("SyncService", "Full authentication successful.")
                body.access
            } else {
                val err = resp.errorBody()?.string() ?: "no body"
                Log.e("SyncService", "Auth failed: HTTP ${resp.code()} — $err")
                null
            }
        } catch (e: Exception) {
            Log.e("SyncService", "Auth exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Called when an API call returns 401 mid-sync (token expired during a long
     * batch upload). Re-runs getValidAccessToken which will use the stored refresh
     * token to get a new pair, or fall back to full login.
     */
    private suspend fun refreshAccessToken(api: ApiService): String? {
        // Clear the stale access token so getValidAccessToken goes straight to refresh.
        getSharedPreferences("api_tokens", Context.MODE_PRIVATE)
            .edit().remove("access_token").apply()
        return getValidAccessToken(api)
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    private suspend fun runUpload(deviceId: String) {
        try {
            val db     = AppDatabase.getDatabase(applicationContext)
            val api    = (applicationContext as TrackMeApp).apiService
            val dao    = db.locationDao()

            val unsynced = dao.getUnsyncedPoints(deviceId)
            if (unsynced.isEmpty()) {
                notify("Nothing to upload", 100, 100)
                delay(2000); stopSelf(); return
            }

            var accessToken = getValidAccessToken(api) ?: run {
                notify("Upload failed: auth error", 0, 100)
                delay(3000); stopSelf(); return
            }

            val total     = unsynced.size
            val batchSize = 100
            val startTime = System.currentTimeMillis()
            var anyBatchSynced = false

            for (i in 0 until total step batchSize) {
                val batch = unsynced.subList(i, (i + batchSize).coerceAtMost(total))

                // Progress + ETA
                val elapsed  = System.currentTimeMillis() - startTime
                val etaStr   = if (i > 0) {
                    val remaining = ((elapsed.toDouble() / i) * total - elapsed).toLong().coerceAtLeast(0)
                    "~${remaining / 60000}m ${(remaining / 1000) % 60}s left"
                } else "Calculating…"
                notify("Uploading: $i / $total  $etaStr", i, total)

                val payload = batch.map {
                    GeoPointRequest(
                        device    = deviceId,
                        latitude  = it.latitude,
                        longitude = it.longitude,
                        timestamp = isoSdf.format(Date(it.timestamp)),
                        accuracy  = it.accuracy
                    )
                }

                var response = api.uploadPoints("Bearer $accessToken", payload)

                // Token expired mid-upload — refresh and retry once.
                if (response.code() == 401) {
                    accessToken = refreshAccessToken(api) ?: run {
                        Log.e("SyncService", "Cannot refresh token — aborting upload.")
                        notify("Upload failed: auth error", 0, total)
                        // Even on partial-failure abort, push a refresh so the
                        // sync-button visibility reflects whatever batches DID land.
                        if (anyBatchSynced) notifyTrackingServiceToRefresh()
                        delay(3000); stopSelf(); return
                    }
                    response = api.uploadPoints("Bearer $accessToken", payload)
                }

                if (response.isSuccessful) {
                    dao.markAsSynced(batch.map { it.copy(isSynced = true) })
                    anyBatchSynced = true
                    Log.d("SyncService", "Batch $i–${i + batch.size} uploaded OK.")

                    // Each batch flips rows from unsynced → synced. The
                    // ForeGroundService notification's sync-button visibility and
                    // its synced/unsynced counts are derived from the DB, so ping
                    // it now so the user sees the counts tick down live instead
                    // of jumping only at the very end of a long upload.
                    notifyTrackingServiceToRefresh()
                } else {
                    val err = response.errorBody()?.string() ?: "no body"
                    Log.e("SyncService", "Batch upload failed: HTTP ${response.code()} — $err")
                }
            }

            notify("Upload complete! ($total points)", 100, 100)
            // Final refresh — guarantees the tracking notification's stats and
            // sync-button state reflect the post-upload DB state.
            notifyTrackingServiceToRefresh()
            delay(2000)
            stopSelf()

        } catch (e: Exception) {
            Log.e("SyncService", "Upload error: ${e.message}")
            notify("Upload failed: ${e.message?.take(40)}", 0, 100)
            // Some batches may have committed before the exception — refresh so
            // the tracking notification doesn't show a stale unsynced count.
            notifyTrackingServiceToRefresh()
            delay(3000)
            stopSelf()
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private suspend fun runDownload(deviceId: String) {
        try {
            val db  = AppDatabase.getDatabase(applicationContext)
            val api = (applicationContext as TrackMeApp).apiService
            val dao = db.locationDao()

            notify("Connecting to server…", 0, 0)

            var accessToken = getValidAccessToken(api) ?: run {
                notify("Download failed: auth error", 0, 100)
                delay(3000); stopSelf(); return
            }

            var response = api.downloadPoints("Bearer $accessToken", DownloadRequest(deviceId))

            if (response.code() == 401) {
                accessToken = refreshAccessToken(api) ?: run {
                    notify("Download failed: auth error", 0, 100)
                    delay(3000); stopSelf(); return
                }
                response = api.downloadPoints("Bearer $accessToken", DownloadRequest(deviceId))
            }

            if (!response.isSuccessful || response.body() == null) {
                Log.e("SyncService", "Download failed: ${response.code()}")
                notify("Download failed: ${response.code()}", 0, 100)
                delay(3000); stopSelf(); return
            }

            val remotePoints = response.body()!!
            Log.d("SyncService", "Downloaded ${remotePoints.size} points from server.")
            notify("Processing ${remotePoints.size} points…", 0, remotePoints.size)

            // Deduplicate against existing timestamps to avoid inserting duplicates.
            val existingTimestamps = dao.getAllTimestamps(deviceId).toHashSet()

            // Calculate the last known total distance so cumulative distances stay correct.
            var runningDistance = dao.getLastPoint(deviceId)?.totalDistance ?: 0f

            val toInsert = mutableListOf<LocationEntity>()
            var prevLat  = dao.getLastPoint(deviceId)?.latitude
            var prevLon  = dao.getLastPoint(deviceId)?.longitude

            remotePoints
                .filter { parseIsoTimestamp(it.timestamp) !in existingTimestamps }
                .sortedBy  { parseIsoTimestamp(it.timestamp) }
                .forEach   { pt ->
                    val ts = parseIsoTimestamp(pt.timestamp)

                    val distFromPrev = if (prevLat != null && prevLon != null) {
                        val result = FloatArray(1)
                        android.location.Location.distanceBetween(prevLat!!, prevLon!!, pt.latitude, pt.longitude, result)
                        result[0]
                    } else 0f

                    runningDistance += distFromPrev
                    prevLat = pt.latitude
                    prevLon = pt.longitude

                    toInsert.add(
                        LocationEntity(
                            userId             = deviceId,
                            latitude           = pt.latitude,
                            longitude          = pt.longitude,
                            timestamp          = ts,
                            speed              = null,
                            accuracy           = pt.accuracy ?: 0f,
                            distanceFromPrevious = distFromPrev,
                            totalDistance      = runningDistance,
                            isSynced           = true   // came from server — already synced
                        )
                    )

                    if (toInsert.size % 100 == 0) notify("Saving…", toInsert.size, remotePoints.size)
                }

            if (toInsert.isNotEmpty()) {
                dao.insertAll(toInsert)
                Log.d("SyncService", "Inserted ${toInsert.size} new points from download.")
                notify("Download complete! (${toInsert.size} new points)", 100, 100)

                // New rows landed in the DB. Tell the tracking foreground service
                // to refresh — without this, its notification keeps showing
                // pre-download stats until the next GPS fix arrives.
                //
                // NOTE: server-downloaded points may include rows with timestamps
                // *older* than the most recent local point. Because we computed
                // their cumulative distance off the local lastPoint (not a strict
                // chronological sort across the full merged set), the tracking
                // notification will show a self-consistent total but it is not a
                // re-baselined recompute the way CSV-import does. If the project
                // requires strict chronological recompute on download, lift the
                // recompute-from-scratch logic out of importCsvFromUri into a
                // shared helper and call it here.
                notifyTrackingServiceToRefresh()
            } else {
                notify("Already up to date.", 100, 100)
            }

            delay(2500)
            stopSelf()

        } catch (e: Exception) {
            Log.e("SyncService", "Download error: ${e.message}")
            notify("Download failed: ${e.message?.take(40)}", 0, 100)
            delay(3000)
            stopSelf()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the Android device ID — used as the "device" key for all API calls. */
    private fun getDeviceID(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

    /** Parses "2026-04-07T12:00:00Z" → epoch millis. Returns 0L on failure. */
    private fun parseIsoTimestamp(iso: String): Long =
        try { isoSdf.parse(iso)?.time ?: 0L } catch (_: Exception) { 0L }

    /**
     * Pokes the tracking foreground service to re-read the DB and refresh the
     * "X km | Y pts" line + sync-button visibility in its persistent notification.
     *
     * Called after any operation that mutates the location_points table without
     * going through ForeGroundService.onLocationResult():
     *   • download → inserts new rows
     *   • upload   → flips isSynced on existing rows (changes unsynced count)
     *
     * If ForeGroundService isn't currently running this is a harmless no-op:
     * Android will deliver the intent to onStartCommand once it starts. We use
     * startService (not startForegroundService) deliberately — the tracking
     * service is already promoted to the foreground when it's alive, and we
     * don't want a transient start during a sync to spin up a new instance and
     * tangle with the existing one.
     */
    private fun notifyTrackingServiceToRefresh() {
        try {
            val intent = Intent(applicationContext, ForeGroundService::class.java).apply {
                action = ForeGroundService.Actions.UPDATE.toString()
            }
            startService(intent)
        } catch (e: Exception) {
            // Most likely cause: the system blocked a background start while
            // the tracking service wasn't already running. That's fine — when
            // it's not alive there are no stale stats to refresh anyway.
            Log.w("SyncService", "Could not notify ForeGroundService: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun notify(text: String, progress: Int, max: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, max))
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val indeterminate = max == 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrackMe Sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(max, progress, indeterminate)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Sync Notifications", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
