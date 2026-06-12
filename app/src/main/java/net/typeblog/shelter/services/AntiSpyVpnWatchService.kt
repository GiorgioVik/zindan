package net.typeblog.shelter.services

import android.app.AlarmManager
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
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.Utility
import net.typeblog.shelter.util.VpnTunnelDetector
import net.typeblog.shelter.util.WorkProfileBatchFreeze

/**
 * Scenario 2: app running → external VPN up → notify (main) → batch-freeze (work profile).
 * Freeze runs inside the work-profile `:vpnwatch` process (local DPM; no cross-profile hop).
 */
class AntiSpyVpnWatchService : Service() {
    private var connectivityManager: ConnectivityManager? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoFreezeRunnable = Runnable { runAutoFreezeOnVpnUp(retry = false) }
    private val pollRunnable = Runnable { pollVpnState() }
    private var vpnPresent = false
    private var foregroundStarted = false
    private var connectivityReceiver: BroadcastReceiver? = null

    private var autoFreezeRetryPending = false
    private val autoFreezeRetryRunnable = Runnable { runAutoFreezeOnVpnUp(retry = true) }

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
        reconcileVpnState("poll")
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
                Log.d(TAG, "CONNECTIVITY_ACTION")
                reconcileVpnState("connectivity")
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
                    reconcileVpnState("vpn-available")
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "vpn callback onLost")
                    reconcileVpnState("vpn-lost")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    reconcileVpnState("vpn-caps")
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
                override fun onAvailable(network: Network) {
                    reconcileVpnState("default-available")
                }

                override fun onLost(network: Network) {
                    reconcileVpnState("default-lost")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    reconcileVpnState("default-caps")
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

    private fun reconcileVpnState(source: String) {
        if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
            Log.d(TAG, "reconcile skipped ($source): launch-gate dummy VPN cycle")
            return
        }
        val active = VpnTunnelDetector.isVpnActive(this)
        if (active == vpnPresent) {
            return
        }
        Log.i(TAG, "vpn edge $source: ${if (active) "activated" else "deactivated"}")
        onVpnStateChanged(active)
    }

    private fun isMainProfileWatcher(): Boolean = !AntiSpyManager.isWorkProfile(this)

    private fun onVpnStateChanged(vpnActive: Boolean) {
        vpnPresent = vpnActive
        handler.removeCallbacks(autoFreezeRunnable)
        if (!vpnActive) {
            if (isMainProfileWatcher()) {
                postVpnStateAlert(R.string.anti_spy_vpn_alert_disconnected_text)
            }
            return
        }
        if (isMainProfileWatcher()) {
            postVpnStateAlert(R.string.anti_spy_vpn_alert_connected_text)
            val list = AntiSpyManager.getAutoFreezeList(this)
            if (list.isNotEmpty()) {
                AntiSpyManager.syncAutoFreezeListToWorkProfile(this, force = true)
                Utility.scheduleFreezeInWorkProfile(this, list)
            } else {
                Log.w(TAG, "VPN up: main auto-freeze list is empty")
            }
        } else {
            handler.postDelayed(autoFreezeRunnable, AUTO_FREEZE_DEBOUNCE_MS)
        }
    }

    private fun postVpnStateAlert(textResId: Int) {
        val title = getString(R.string.anti_spy_monitor_notification_title)
        val text = getString(textResId)
        Utility.postUserAlert(this, VPN_STATE_NOTIFICATION_ID, title, text)
    }

    private fun runAutoFreezeOnVpnUp(retry: Boolean) {
        if (isMainProfileWatcher()) {
            return
        }
        if (!VpnTunnelDetector.isVpnActive(this)) {
            Log.d(TAG, "auto-freeze cancelled: vpn no longer active")
            vpnPresent = false
            autoFreezeRetryPending = false
            return
        }
        val list = AntiSpyManager.getAutoFreezeList(this)
        if (list.isEmpty()) {
            if (!retry && !autoFreezeRetryPending) {
                autoFreezeRetryPending = true
                Log.w(TAG, "auto-freeze: work list empty, retry in ${AUTO_FREEZE_RETRY_MS}ms")
                handler.postDelayed(autoFreezeRetryRunnable, AUTO_FREEZE_RETRY_MS)
                return
            }
            Log.w(TAG, "auto-freeze skipped: auto-freeze list is empty in work profile")
            autoFreezeRetryPending = false
            return
        }
        autoFreezeRetryPending = false
        handler.removeCallbacks(autoFreezeRetryRunnable)
        VpnTunnelDetector.logDiagnostics(this)
        Log.i(TAG, "auto-freeze in work profile on VPN up: ${list.size} apps (VPN stays active)")
        val frozen = WorkProfileBatchFreeze.freezeList(this, list)
        if (frozen > 0) {
            Utility.postVpnAutoFreezeSuccessAlert(this)
            Utility.scheduleAppListRefresh(this)
            Log.i(TAG, "auto-freeze done: $frozen apps hidden")
        } else {
            Log.w(TAG, "auto-freeze: freezeList returned 0")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved, scheduling restart")
        scheduleRestartIfNeeded()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        scheduleRestartIfNeeded()
        handler.removeCallbacks(autoFreezeRunnable)
        handler.removeCallbacks(autoFreezeRetryRunnable)
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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "exact restart alarm denied, using inexact", e)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
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
        private const val AUTO_FREEZE_DEBOUNCE_MS = 2500L
        private const val AUTO_FREEZE_RETRY_MS = 2000L
        private const val VPN_POLL_MS = 2000L
        private const val SYNC_STATE_COOLDOWN_MS = 3000L

        @Volatile
        private var lastSyncStateElapsed = 0L

        fun syncState(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSyncStateElapsed < SYNC_STATE_COOLDOWN_MS) {
                return
            }
            lastSyncStateElapsed = now
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
