package feicheiel.technologies.trackme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

import feicheiel.technologies.trackme.api.GeoApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class TrackMeApp: Application() {

    lateinit var apiService: GeoApi

    override fun onCreate() {
        super.onCreate()
        
        val retrofit = Retrofit.Builder()
            .baseUrl("https://your-api-base-url.com/") // TODO: Replace with your actual base URL
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        apiService = retrofit.create(GeoApi::class.java)

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
}