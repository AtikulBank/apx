package ai.apx.ctrl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        ContextCompat.startForegroundService(
            c, Intent(c, ApxService::class.java).setAction(ApxService.ACT_START)
        )
    }
}