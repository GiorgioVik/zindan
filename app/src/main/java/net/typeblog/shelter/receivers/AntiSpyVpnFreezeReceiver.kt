package net.typeblog.shelter.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.Utility

/**
 * Runs VPN-up batch freeze in the main app process (not {@code :vpnwatch}).
 * Cross-profile delivery to work profile is more reliable from here than from the VPN FGS process.
 */
class AntiSpyVpnFreezeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }
        val pendingResult = goAsync()
        try {
            handleVpnBatchFreeze(context.applicationContext)
        } finally {
            pendingResult.finish()
        }
    }

    private fun handleVpnBatchFreeze(app: Context) {
        LocalStorageManager.initialize(app)
        if (AntiSpyManager.isWorkProfile(app)) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastHandleElapsedMs < HANDLE_DEBOUNCE_MS) {
            Log.d(TAG, "VPN batch freeze: debounced")
            return
        }
        lastHandleElapsedMs = now

        val list = Utility.normalizeStringList(AntiSpyManager.getAutoFreezeList(app))
        if (list.isEmpty()) {
            Log.w(TAG, "VPN batch freeze: auto-freeze list is empty")
            return
        }
        Log.i(TAG, "VPN batch freeze requested via public freeze-all path, list=${list.size}")
        Log.i(TAG, "VPN batch freeze main packages: ${list.joinToString(",")}")
        AntiSpyManager.syncAutoFreezeListToWorkProfile(app, force = true)
        val publicLaunched = AntiSpyManager.runBatchFreezeAllFromVpn(app)
        if (!publicLaunched) {
            Log.w(TAG, "VPN public freeze-all path failed")
        }
        Log.i(TAG, "VPN batch freeze delivery: public=$publicLaunched")
    }

    companion object {
        private const val TAG = "AntiSpyVpnFreeze"
        private const val HANDLE_DEBOUNCE_MS = 1500L
        @Volatile
        private var lastHandleElapsedMs = 0L
        const val ACTION = "net.typeblog.shelter.action.VPN_BATCH_FREEZE"
    }
}
