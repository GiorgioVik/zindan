package net.typeblog.shelter.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.AntiSpyVpnWatchHealth
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.Utility

/**
 * After device reboot, schedule a one-time batch freeze when Zindan starts with Anti Spy on.
 */
class AntiSpyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val app = context.applicationContext
        LocalStorageManager.initialize(app)
        val storage = LocalStorageManager.getInstance()
        AntiSpyManager.onDeviceBoot(storage)
        Utility.trimApplicationCache(app)
        AntiSpyManager.syncVpnWatchEverywhere(app)
        AntiSpyVpnWatchHealth.scheduleWatchdog(app)
    }
}
