package feicheiel.technologies.trackme

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Starts the location-tracking foreground service on behalf of [BootReceiver].
 *
 * Android 15+ forbids starting a `location`-type foreground service directly from a
 * BOOT_COMPLETED broadcast receiver's execution window. Running it from WorkManager
 * (expedited work) instead is Google's documented workaround for that restriction.
 */
class BootStartTrackingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val serviceIntent = Intent(applicationContext, ForeGroundService::class.java).apply {
            action = ForeGroundService.Actions.START.toString()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }
        return Result.success()
    }
}
