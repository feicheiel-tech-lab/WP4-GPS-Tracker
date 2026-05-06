package feicheiel.technologies.trackme

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import feicheiel.technologies.trackme.api.ApiService
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class TrackMeApp : Application() {

    /** Single Retrofit service wired to the geologger endpoint. */
    lateinit var apiService: ApiService
        private set

    override fun onCreate() {
        super.onCreate()

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        apiService = Retrofit.Builder()
            .baseUrl("https://geologger.pathplotter.net/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)

        // Schedule the nightly midnight upload via AlarmManager.
        // BootReceiver will re-schedule after a device reboot.
        MidnightSyncReceiver.scheduleMidnightSync(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "running_channel",
                    "Tracking Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    "sync_channel",
                    "Sync Notifications",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
