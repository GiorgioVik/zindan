package net.typeblog.shelter.util

import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.typeblog.shelter.R

/**
 * Anti Spy launch path: clear VPN via pseudo VpnService cycles, then proceed.
 */
object AntiSpyLaunchGate {
    const val BROADCAST_LAUNCH_BLOCKED_VPN =
        "net.typeblog.shelter.broadcast.ANTI_SPY_LAUNCH_BLOCKED_VPN"
    const val EXTRA_BLOCK_REASON = "block_reason"
    const val EXTRA_PACKAGE_NAME = "package_name"

    const val REASON_VPN_STILL_ACTIVE = 1
    const val REASON_VPN_PERMISSION_REQUIRED = 2
    const val REASON_FAILED = 3

    fun interface BlockedCallback {
        fun onBlocked(reason: Int)
    }

    fun needsVpnClear(context: Context, @Suppress("UNUSED_PARAMETER") storage: LocalStorageManager): Boolean =
        VpnTunnelDetector.isVpnActive(context.applicationContext)

    fun runBeforeLaunch(
        context: Context,
        storage: LocalStorageManager,
        packageName: String,
        onProceed: Runnable,
        onBlocked: BlockedCallback?
    ) {
        if (!needsVpnClear(context, storage)) {
            onProceed.run()
            return
        }

        AntiSpyDummyVpnDisconnector.tryClearVpnAsync(context) { result ->
            if (result == AntiSpyDummyVpnDisconnector.RESULT_CLEARED) {
                onProceed.run()
                return@tryClearVpnAsync
            }
            val reason = when (result) {
                AntiSpyDummyVpnDisconnector.RESULT_VPN_PERMISSION_REQUIRED ->
                    REASON_VPN_PERMISSION_REQUIRED
                AntiSpyDummyVpnDisconnector.RESULT_VPN_STILL_ACTIVE ->
                    REASON_VPN_STILL_ACTIVE
                else -> REASON_FAILED
            }
            if (reason == REASON_VPN_PERMISSION_REQUIRED) {
                onBlocked?.onBlocked(reason)
                return@tryClearVpnAsync
            }
            notifyLaunchBlocked(context, reason, packageName)
            onBlocked?.onBlocked(reason)
        }
    }

    fun notifyLaunchBlocked(context: Context, reason: Int, packageName: String) {
        val app = context.applicationContext
        Toast.makeText(app, messageForReason(app, reason), Toast.LENGTH_LONG).show()
        val broadcast = Intent(BROADCAST_LAUNCH_BLOCKED_VPN)
        broadcast.putExtra(EXTRA_BLOCK_REASON, reason)
        if (!TextUtils.isEmpty(packageName)) {
            broadcast.putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        LocalBroadcastManager.getInstance(app).sendBroadcast(broadcast)
    }

    private fun messageForReason(context: Context, reason: Int): String {
        return if (reason == REASON_VPN_PERMISSION_REQUIRED) {
            context.getString(R.string.anti_spy_vpn_permission_required)
        } else {
            context.getString(R.string.anti_spy_vpn_block_launch_manual)
        }
    }
}
