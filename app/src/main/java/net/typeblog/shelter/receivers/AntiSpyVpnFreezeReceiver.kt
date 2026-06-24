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
            Utility.postUserAlert(
                app,
                DIAG_NOTIFICATION_ID,
                "Zindan VPN-диагностика",
                "RECEIVER: список основного профиля пуст",
            )
            return
        }
        Log.i(TAG, "VPN batch freeze requested, list=${list.size}")
        val started = Utility.startBatchFreezeInWorkProfile(app, list)
        if (!started) {
            val launched = Utility.launchFreezeInWorkProfile(app, list)
            if (!launched) {
                Utility.scheduleFreezeInWorkProfile(app, list)
                Log.w(TAG, "VPN batch freeze: cross-profile delivery failed, AlarmManager fallback")
            }
        }
        Utility.postUserAlert(
            app,
            DIAG_NOTIFICATION_ID,
            "Zindan VPN-диагностика",
            "RECEIVER: список=${list.size}, сервис в work=$started",
        )
    }

    companion object {
        private const val TAG = "AntiSpyVpnFreeze"
        private const val DIAG_NOTIFICATION_ID = 0xe49dc
        private const val HANDLE_DEBOUNCE_MS = 5000L
        @Volatile
        private var lastHandleElapsedMs = 0L
        const val ACTION = "net.typeblog.shelter.action.VPN_BATCH_FREEZE"
    }
}
