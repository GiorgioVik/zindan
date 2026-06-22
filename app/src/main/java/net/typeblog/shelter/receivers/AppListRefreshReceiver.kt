package net.typeblog.shelter.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.Utility

/**
 * Runs app-list refresh in the personal profile default process (not {@code :vpnwatch}).
 */
class AppListRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            return
        }
        val pendingResult = goAsync()
        try {
            val app = context.applicationContext
            if (AntiSpyManager.isWorkProfile(app)) {
                // PendingIntent/broadcast may resolve in the work user — forward to personal profile.
                Log.d(TAG, "forward app-list refresh to personal profile")
                Utility.scheduleAppListRefreshDelivery(app)
                return
            }
            Log.d(TAG, "app-list refresh delivery")
            Utility.deliverAppListRefreshInMainProcess(app)
        } finally {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "AppListRefresh"
        const val ACTION = "net.typeblog.shelter.action.DELIVER_APP_LIST_REFRESH"
    }
}
