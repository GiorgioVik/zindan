package net.typeblog.shelter.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.typeblog.shelter.services.AntiSpyVpnWatchService
import net.typeblog.shelter.util.AntiSpyVpnWatchHealth
import net.typeblog.shelter.util.LocalStorageManager

/**
 * Periodic self-check that `:vpnwatch` is alive; retries FGS start when background launch failed.
 */
class AntiSpyVpnWatchWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        LocalStorageManager.initialize(app)
        when (intent.action) {
            AntiSpyVpnWatchHealth.ACTION_WATCHDOG -> {
                Log.d(TAG, "watchdog tick")
                AntiSpyVpnWatchHealth.runWatchdog(app)
            }
            AntiSpyVpnWatchHealth.ACTION_FGS_RETRY -> {
                Log.i(TAG, "FGS retry tick")
                AntiSpyVpnWatchService.syncState(app, allowBackgroundRetry = true)
            }
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "VpnWatchWatchdog"
    }
}
