package feicheiel.technologies.trackme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

import feicheiel.technologies.trackme.api.GeoApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TrackMeApp: Application() {

    lateinit var apiService: GeoApi

    override fun onCreate() {
        super.onCreate()
        
        val retrofit = Retrofit.Builder()
            .baseUrl("http://10.232.1.42:8000/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        apiService = retrofit.create(GeoApi::class.java)

        scheduleMidnightSync()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "running_channel",
                "Running Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun scheduleMidnightSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Calculate delay until 00:00 GMT
        val currentDate = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
        val dueDate = Calendar.getInstance(TimeZone.getTimeZone("GMT")).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(currentDate)) {
                add(Calendar.HOUR_OF_DAY, 24)
            }
        }

        val initialDelay = dueDate.timeInMillis - currentDate.timeInMillis

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag("midnight_sync")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "midnight_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
