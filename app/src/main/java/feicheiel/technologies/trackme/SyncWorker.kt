package feicheiel.technologies.trackme

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log

import feicheiel.technologies.trackme.api.GeoPointRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val apiService = (applicationContext as TrackMeApp).apiService

        // Retrieve the current user ID. If not found, we cannot sync.
        val userId = prefs.getString("current_user_id", null) ?: return Result.failure()
        var authToken = prefs.getString("auth_token", "") ?: ""

        val unsyncedPoints = database.locationDao().getUnsyncedPoints(userId)

        if (unsyncedPoints.isEmpty()) {
            Log.d("SyncWorker", "No new points to sync for user: $userId")
            return Result.success()
        }

        return try {
            val batchSize = 100
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            
            for (i in 0 until unsyncedPoints.size step batchSize) {
                val end = (i + batchSize).coerceAtMost(unsyncedPoints.size)
                val batch = unsyncedPoints.subList(i, end)
                
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
                    val refreshToken = prefs.getString("refresh_token", "") ?: ""
                    if (refreshToken.isNotEmpty()) {
                        val refreshResponse = apiService.refreshToken(feicheiel.technologies.trackme.api.RefreshRequest(refreshToken))
                        if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                            val newAccessToken = refreshResponse.body()!!.access
                            prefs.edit().putString("auth_token", newAccessToken).apply()
                            authToken = newAccessToken
                            response = apiService.uploadPoints("Bearer $authToken", geoRequests)
                        }
                    }
                }
                
                if (response.isSuccessful) {
                    val syncedBatch = batch.map { it.copy(isSynced = true) }
                    database.locationDao().markAsSynced(syncedBatch)
                } else {
                    Log.e("SyncWorker", "Batch upload failed: ${response.code()}")
                    return Result.retry()
                }
            }
            
            Log.d("SyncWorker", "Successfully synced all points for user: $userId")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error syncing points: ${e.message}")
            Result.retry()
        }
    }
}
