package feicheiel.technologies.trackme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_REBOOT) {
            
            val sharedPref = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            val isLoggedIn = sharedPref.getString("user_id", null) != null
            
            if (isLoggedIn) {
                val serviceIntent = Intent(context, ForeGroundService::class.java).apply {
                    action = ForeGroundService.Actions.START.toString()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}