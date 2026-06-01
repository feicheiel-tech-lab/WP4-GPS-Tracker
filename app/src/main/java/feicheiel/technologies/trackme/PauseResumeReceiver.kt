package feicheiel.technologies.trackme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import android.os.Build

class PauseResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val resumeIntent = Intent(context, ForeGroundService::class.java).apply {
            action = ForeGroundService.Actions.RESUME.toString()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(resumeIntent)
        } else {
            context.startService(resumeIntent)
        }
    }
}
