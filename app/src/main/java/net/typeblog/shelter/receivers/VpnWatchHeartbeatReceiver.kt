package net.typeblog.shelter.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.typeblog.shelter.util.AntiSpyVpnWatchHealth
import net.typeblog.shelter.util.LocalStorageManager

/** Work profile `:vpnwatch` → main profile heartbeat mirror for Settings diagnostics. */
class VpnWatchHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AntiSpyVpnWatchHealth.ACTION_HEARTBEAT) {
            return
        }
        LocalStorageManager.initialize(context.applicationContext)
        val at = intent.getLongExtra(AntiSpyVpnWatchHealth.EXTRA_AT, System.currentTimeMillis())
        val vpnActive = intent.getBooleanExtra(AntiSpyVpnWatchHealth.EXTRA_VPN_ACTIVE, false)
        AntiSpyVpnWatchHealth.recordWorkHeartbeatOnMain(at, vpnActive)
    }
}
