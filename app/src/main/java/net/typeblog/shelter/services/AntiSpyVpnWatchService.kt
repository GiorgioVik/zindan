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
import net.typeblog.shelter.util.WorkProfileBatchFreeze
import net.typeblog.shelter.util.ZindanToast

/**
 * Background VPN watcher. Batch-freeze on VPN-up is triggered from the main profile.
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
    private var vpnFreezeInFlight = false
    private var vpnFreezeDoneForSession = false
    private var vpnSessionHadFreeze = false

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
        } else if (active && !isMainProfileWatcher() &&
            !vpnFreezeDoneForSession &&
            !AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()
        ) {
            // Edge-based triggering misses the case where the work watcher starts with the VPN
            // already up, or the VPN network churns (new netId) without ever dropping to false.
            // Re-attempt the freeze on each poll until it has run once for this VPN session.
            handler.post(freezeRunnable)
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
        if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
            Log.d(TAG, "vpn state ignored during displacement")
            return
        }
        if (vpnActive == vpnPresent) return
        vpnPresent = vpnActive
        handler.removeCallbacks(freezeRunnable)
        if (!vpnActive) {
            vpnFreezeDoneForSession = false
            vpnSessionHadFreeze = false
            AntiSpyVpnPromptManager.onVpnSessionEnded()
            if (isMainProfileWatcher()) {
                postVpnStateAlert(R.string.anti_spy_vpn_alert_disconnected_text)
            }
            return
        }
        if (isMainProfileWatcher()) {
            postVpnStateAlert(R.string.anti_spy_vpn_alert_connected_text)
        }
        handler.post(freezeRunnable)
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
            Log.d(TAG, "freeze skipped: dummy vpn cycle")
            return
        }
        if (!VpnTunnelDetector.isVpnActive(this)) {
            Log.d(TAG, "freeze cancelled: vpn no longer active")
            vpnPresent = false
            AntiSpyVpnPromptManager.onVpnSessionEnded()
            return
        }
        VpnTunnelDetector.logDiagnostics(this)

        if (isMainProfileWatcher()) {
            // The personal profile has no privilege to freeze work-profile apps and cannot
            // start a service/activity across profiles from the background. Only the work-profile
            // watcher (profile owner) can freeze directly via DevicePolicyManager, so it owns this.
            postFreezeDiagnostic("MAIN: VPN ON (морозит work-профиль)")
            return
        }

        // Work profile is the profile owner: freeze directly via DevicePolicyManager.
        val list = AntiSpyManager.getAutoFreezeList(this)
        if (list.isEmpty()) {
            // List is synced into work prefs whenever FREEZE_ALL_IN_LIST runs (startup/manual).
            postFreezeDiagnostic("WORK: список пуст — открой Zindan один раз")
            return
        }
        if (vpnFreezeInFlight) return
        vpnFreezeInFlight = true
        try {
            val frozen = WorkProfileBatchFreeze.freezeList(this, list)
            val stillVisible = WorkProfileBatchFreeze.countStillVisible(this, list)
            if (frozen > 0) {
                vpnSessionHadFreeze = true
            }
            // Keep retrying on poll while any auto-freeze app (e.g. foreground VPN client) stays visible.
            vpnFreezeDoneForSession = stillVisible == 0
            Log.i(
                TAG,
                "VPN batch-freeze in work profile: $frozen of ${list.size}, still visible=$stillVisible"
            )
            if (stillVisible > 0) {
                postFreezeDiagnostic("WORK: не заморожено $stillVisible — повтор…")
            } else {
                postFreezeDiagnostic("WORK: заморожено $frozen из ${list.size}")
                Utility.notifyBatchFreezeComplete(this, vpnSessionHadFreeze)
                vpnSessionHadFreeze = false
            }
        } finally {
            handler.postDelayed({ vpnFreezeInFlight = false }, 1500L)
        }
    }

    private fun postFreezeDiagnostic(text: String) {
        val id = if (isMainProfileWatcher()) DIAG_MAIN_NOTIFICATION_ID else DIAG_WORK_NOTIFICATION_ID
        Utility.postUserAlert(this, id, "Zindan VPN-диагностика", text)
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
            // setAndAllowWhileIdle is inexact and does NOT require SCHEDULE_EXACT_ALARM /
            // USE_EXACT_ALARM, which newer Android no longer grants by default.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
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
        private const val DIAG_MAIN_NOTIFICATION_ID = 0xe49da
        private const val DIAG_WORK_NOTIFICATION_ID = 0xe49db
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
