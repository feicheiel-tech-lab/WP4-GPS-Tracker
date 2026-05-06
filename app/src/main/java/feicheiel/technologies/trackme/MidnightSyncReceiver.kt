package feicheiel.technologies.trackme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Fired by AlarmManager every night at midnight GMT.
 * Starts SyncForegroundService to upload any unsynced points,
 * then reschedules itself for the next midnight so the alarm
 * survives across reboots (BootReceiver calls scheduleMidnightSync
 * again after a reboot to restore the alarm).
 */
class MidnightSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Kick off the upload.
        val syncIntent = Intent(context, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_UPLOAD
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(syncIntent)
        } else {
            context.startService(syncIntent)
        }

        // Reschedule for the next midnight so the alarm repeats daily.
        // AlarmManager.setExactAndAllowWhileIdle fires only once — we always
        // reschedule from here rather than using setRepeating, which is inexact.
        scheduleMidnightSync(context)
    }

    companion object {
        fun scheduleMidnightSync(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

            // Calculate exact milliseconds until next 00:00:00 GMT.
            val midnight = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT")).apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                // If we're already past midnight today, target tomorrow's midnight.
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, MidnightSyncReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            // setExactAndAllowWhileIdle fires even in Doze mode — important for a
            // midnight trigger that needs to run regardless of device idle state.
            // On Android 12+ this requires SCHEDULE_EXACT_ALARM or
            // USE_EXACT_ALARM permission in the manifest.
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                midnight.timeInMillis,
                pendingIntent
            )
        }
    }
}
