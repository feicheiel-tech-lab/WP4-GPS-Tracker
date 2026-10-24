package feicheiel.technologies.trackme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_REBOOT) {

            val sharedPref = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            val isLoggedIn = sharedPref.getString("user_id", null) != null

            if (isLoggedIn) {
                // Restart the tracking service. On Android 15+ a `location`-type foreground
                // service can't be started directly from a BOOT_COMPLETED receiver, so hand
                // it off to WorkManager's expedited work, which runs outside that restriction.
                val startRequest = OneTimeWorkRequestBuilder<BootStartTrackingWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "boot_start_tracking",
                    ExistingWorkPolicy.REPLACE,
                    startRequest
                )

                // Restore the midnight sync alarm — AlarmManager alarms are lost on reboot.
                MidnightSyncReceiver.scheduleMidnightSync(context)
            }
        }
    }
}
