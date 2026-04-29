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
        val authToken = prefs.getString("auth_token", "") ?: ""

        // Fetch all unsynced points for this specific user
        val points = database.locationDao().getUnsyncedPoints(userId)

        if (points.isEmpty()) {
            Log.d("SyncWorker", "No new points to sync for user: $userId")
            return Result.success()
        }

        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val geoRequests = points.map { 
                GeoPointRequest(
                    device = userId,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    timestamp = sdf.format(Date(it.timestamp))
                )
            }

            val response = apiService.uploadPoints("Bearer $authToken", geoRequests)
            
            if (response.isSuccessful) {
                val syncedPoints = points.map { it.copy(isSynced = true) }
                database.locationDao().markAsSynced(syncedPoints)
                Log.d("SyncWorker", "Successfully synced ${points.size} points for user: $userId")
                Result.success()
            } else {
                Log.e("SyncWorker", "Upload failed: ${response.code()}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error syncing points: ${e.message}")
            Result.retry()
        }
    }
}
