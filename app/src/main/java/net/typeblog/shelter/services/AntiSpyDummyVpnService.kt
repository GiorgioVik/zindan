package net.typeblog.shelter.services

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.typeblog.shelter.R
import java.io.IOException

/**
 * Brief pseudo-VPN to displace an active third-party tunnel (same approach as kick_vpns).
 * No foreground notification тАФ the tunnel lives only for a few hundred milliseconds.
 */
class AntiSpyDummyVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_ESTABLISH -> {
                startTunnel()
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startTunnel() {
        Log.d(TAG, "startTunnel pid=${Process.myPid()}")
        if (sTunnel != null) {
            notifyEstablished()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            Log.w(TAG, "VpnService.prepare needs user consent pid=${Process.myPid()}")
            notifyPermissionRequired()
            stopSelf()
            return
        }

        try {
            val builder = Builder()
                .setSession(getString(R.string.anti_spy_dummy_vpn_session))
                .addAddress("10.255.255.1", 32)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                builder.setBlocking(false)
            }
            val tunnel = builder.establish()
            if (tunnel == null) {
                Log.w(TAG, "establish() returned null")
                notifyFailed()
                stopSelf()
                return
            }
            sTunnel = tunnel
            notifyEstablished()
        } catch (e: Exception) {
            Log.w(TAG, "establish failed", e)
            notifyFailed()
            stopSelf()
        }
    }

    private fun stopTunnel() {
        sTunnel?.let { tunnel ->
            try {
                tunnel.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing tunnel", e)
            }
            sTunnel = null
        }
        notifyDisconnected()
        stopSelf()
    }

    private fun notifyEstablished() {
        sendStatusBroadcast(BROADCAST_ESTABLISHED)
    }

    private fun notifyFailed() {
        sendStatusBroadcast(BROADCAST_FAILED)
    }

    private fun notifyPermissionRequired() {
        sendStatusBroadcast(BROADCAST_PERMISSION_REQUIRED)
    }

    private fun notifyDisconnected() {
        sendStatusBroadcast(BROADCAST_DISCONNECTED)
    }

    /** LocalBroadcastManager for main process; package broadcast for `:vpnwatch`. */
    private fun sendStatusBroadcast(action: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sTunnel?.let { tunnel ->
            try {
                tunnel.close()
            } catch (_: IOException) {
            }
            sTunnel = null
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AntiSpyDummyVpn"

        const val ACTION_ESTABLISH = "net.typeblog.shelter.action.ANTI_SPY_DUMMY_VPN_ESTABLISH"
        const val ACTION_DISCONNECT = "net.typeblog.shelter.action.ANTI_SPY_DUMMY_VPN_DISCONNECT"
        const val BROADCAST_ESTABLISHED = "net.typeblog.shelter.broadcast.ANTI_SPY_DUMMY_VPN_ESTABLISHED"
        const val BROADCAST_FAILED = "net.typeblog.shelter.broadcast.ANTI_SPY_DUMMY_VPN_FAILED"
        const val BROADCAST_PERMISSION_REQUIRED =
            "net.typeblog.shelter.broadcast.ANTI_SPY_DUMMY_VPN_PERMISSION_REQUIRED"
        const val BROADCAST_DISCONNECTED =
            "net.typeblog.shelter.broadcast.ANTI_SPY_DUMMY_VPN_DISCONNECTED"

        @Volatile
        private var sTunnel: ParcelFileDescriptor? = null

        fun isTunnelActive(): Boolean = sTunnel != null
    }
}
