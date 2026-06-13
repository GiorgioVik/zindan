package net.typeblog.shelter.services

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import net.typeblog.shelter.R
import net.typeblog.shelter.util.AntiSpyDummyVpnDisconnector
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.AntiSpyVpnPromptManager
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.Utility
import net.typeblog.shelter.util.VpnTunnelDetector
import net.typeblog.shelter.util.ZindanToast

/**
 * Background VPN watcher while Anti Spy (golden shield) is on.
 * Runs batch freeze when VPN appears (MacroDroid-style NetworkCallback).
 */
class AntiSpyVpnWatchService : Service() {
    private var connectivityManager: ConnectivityManager? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val freezeRunnable = Runnable { maybeFreezeAllForVpn() }
    private val pollRunnable = Runnable { pollVpnState() }
    private var vpnPresent = false
    private var foregroundStarted = false
    private var connectivityReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.initialize(applicationContext)
        Log.i(TAG, "onCreate pid=${Process.myPid()} work=${AntiSpyManager.isWorkProfile(this)}")
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        ensureForeground()
        registerVpnCallbacks()
        registerConnectivityReceiver()
        vpnPresent = VpnTunnelDetector.isVpnActive(this)
        Log.d(TAG, "initial vpn=$vpnPresent")
        handler.postDelayed(pollRunnable, VPN_POLL_MS)
    }

    private fun pollVpnState() {
        val active = scanVpnActive()
        if (active && !vpnPresent) {
            if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
                Log.d(TAG, "poll: vpn active but reactions suppressed")
            } else {
                Log.i(TAG, "poll: vpn became active")
                onVpnStateChanged(true)
            }
        } else if (!active && vpnPresent) {
            onVpnStateChanged(false)
        }
        handler.postDelayed(pollRunnable, VPN_POLL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        return START_STICKY
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        try {
            val notification = Utility.buildNotification(
                this,
                true,
                getString(R.string.anti_spy_monitor_notification_title),
                getString(R.string.anti_spy_monitor_notification_title),
                getString(R.string.anti_spy_monitor_notification_text),
                R.drawable.ic_lock_open_white_24dp,
            )
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
    }

    private fun registerConnectivityReceiver() {
        if (connectivityReceiver != null) return
        connectivityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (VpnTunnelDetector.isVpnActive(context)) {
                    Log.d(TAG, "CONNECTIVITY_ACTION vpn active")
                    onVpnStateChanged(true)
                }
            }
        }
        try {
            registerReceiver(
                connectivityReceiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
            )
        } catch (e: Exception) {
            Log.w(TAG, "CONNECTIVITY_ACTION receiver failed", e)
            connectivityReceiver = null
        }
    }

    private fun registerVpnCallbacks() {
        val cm = connectivityManager ?: return

        if (vpnCallback == null) {
            val request = buildVpnNetworkRequest()
            vpnCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "vpn callback onAvailable")
                    onVpnStateChanged(true)
                }

                override fun onLost(network: Network) {
                    onVpnStateChanged(scanVpnActive())
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (vpn) {
                        onVpnStateChanged(true)
                    } else {
                        onVpnStateChanged(scanVpnActive())
                    }
                }
            }
            try {
                cm.registerNetworkCallback(request, vpnCallback!!)
            } catch (e: Exception) {
                Log.e(TAG, "registerNetworkCallback failed", e)
                vpnCallback = null
            }
        }

        if (defaultCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            defaultCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (vpn) {
                        Log.d(TAG, "default network vpn capabilities")
                        onVpnStateChanged(true)
                    }
                }
            }
            try {
                cm.registerDefaultNetworkCallback(defaultCallback!!)
            } catch (e: Exception) {
                Log.w(TAG, "registerDefaultNetworkCallback failed", e)
                defaultCallback = null
            }
        }
    }

    private fun onVpnStateChanged(vpnActive: Boolean) {
        if (vpnActive == vpnPresent) return
        vpnPresent = vpnActive
        handler.removeCallbacks(freezeRunnable)
        if (!vpnActive) {
            AntiSpyVpnPromptManager.onVpnSessionEnded()
            if (isMainProfileWatcher()) {
                postVpnStateAlert(R.string.anti_spy_vpn_alert_disconnected_text)
            }
            return
        }
        if (isMainProfileWatcher()) {
            postVpnStateAlert(R.string.anti_spy_vpn_alert_connected_text)
        }
        handler.postDelayed(freezeRunnable, FREEZE_DEBOUNCE_MS)
    }

    private fun isMainProfileWatcher(): Boolean = !AntiSpyManager.isWorkProfile(this)

    private fun postVpnStateAlert(textResId: Int) {
        val title = getString(R.string.anti_spy_monitor_notification_title)
        val text = getString(textResId)
        ZindanToast.show(this, text)
        Utility.postUserAlert(this, VPN_STATE_NOTIFICATION_ID, title, text)
    }

    private fun maybeFreezeAllForVpn() {
        if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
            Log.d(TAG, "prompt skipped: dummy vpn cycle")
            return
        }
        if (AntiSpyVpnPromptManager.isPromptActive()) {
            Log.d(TAG, "prompt skipped: already showing")
            return
        }
        if (AntiSpyVpnPromptManager.isDeclinedForCurrentVpnSession()) {
            Log.d(TAG, "prompt skipped: user declined for this VPN session")
            return
        }
        if (!VpnTunnelDetector.isVpnActive(this)) {
            Log.d(TAG, "prompt cancelled: vpn no longer active")
            vpnPresent = false
            AntiSpyVpnPromptManager.onVpnSessionEnded()
            return
        }
        VpnTunnelDetector.logDiagnostics(this)
        Log.i(TAG, "VPN detected, brief dummy displacement then user prompt")
        AntiSpyDummyVpnDisconnector.tryClearVpnAsync(this) { result ->
            if (AntiSpyVpnPromptManager.isDeclinedForCurrentVpnSession()) {
                return@tryClearVpnAsync
            }
            if (AntiSpyVpnPromptManager.isPromptActive()) {
                return@tryClearVpnAsync
            }
            Log.i(TAG, "dummy displacement result=$result")
            when (result) {
                AntiSpyDummyVpnDisconnector.RESULT_CLEARED ->
                    AntiSpyVpnPromptManager.showPrompt(this)
                AntiSpyDummyVpnDisconnector.RESULT_VPN_PERMISSION_REQUIRED ->
                    AntiSpyVpnPromptManager.showVpnPermissionNeeded(this)
                else ->
                    AntiSpyVpnPromptManager.showDisplacementFailed(this)
            }
        }
    }

    private fun scanVpnActive(): Boolean = VpnTunnelDetector.isVpnActive(this)

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved, scheduling restart")
        scheduleRestartIfNeeded()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        scheduleRestartIfNeeded()
        handler.removeCallbacks(freezeRunnable)
        handler.removeCallbacks(pollRunnable)
        connectivityReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            connectivityReceiver = null
        }
        connectivityManager?.let { cm ->
            vpnCallback?.let { callback ->
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                }
                vpnCallback = null
            }
            defaultCallback?.let { callback ->
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                }
                defaultCallback = null
            }
        }
        super.onDestroy()
    }

    private fun scheduleRestartIfNeeded() {
        try {
            val app = applicationContext
            val intent = Intent(app, AntiSpyVpnWatchService::class.java)
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    app,
                    0xE49D2,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getService(
                    app,
                    0xE49D2,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            val trigger = SystemClock.elapsedRealtime() + 1500
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
            Log.i(TAG, "restart scheduled via AlarmManager")
        } catch (e: Exception) {
            Log.w(TAG, "scheduleRestart failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AntiSpyVpnWatch"
        private const val NOTIFICATION_ID = 0xe49d0
        private const val VPN_STATE_NOTIFICATION_ID = 0xe49d1
        private const val FREEZE_DEBOUNCE_MS = 1500L
        private const val VPN_POLL_MS = 2000L

        fun syncState(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, AntiSpyVpnWatchService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                Log.d(TAG, "syncState: start requested pid=${Process.myPid()}")
            } catch (e: SecurityException) {
                Log.w(TAG, "FGS start denied", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "FGS start failed", e)
            } catch (e: RuntimeException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
                ) {
                    Log.w(TAG, "FGS not allowed from background; will retry from foreground", e)
                } else {
                    throw e
                }
            }
        }

        private fun buildVpnNetworkRequest(): NetworkRequest {
            return try {
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            } catch (_: IllegalArgumentException) {
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .build()
            }
        }
    }
}
