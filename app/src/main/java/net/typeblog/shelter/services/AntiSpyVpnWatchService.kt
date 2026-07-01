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
import net.typeblog.shelter.util.AntiSpyVpnWatchHealth
import net.typeblog.shelter.util.WorkProfileBatchFreeze
import net.typeblog.shelter.util.WorkProfileVpnFreezeCoordinator
import net.typeblog.shelter.util.ZindanToast

/**
 * Background VPN watcher. Batch-freeze on VPN-up uses the main-profile auto-freeze list.
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
    private var freezeCompleteReceiver: BroadcastReceiver? = null
    private var vpnFreezeInFlight = false
    private var vpnFreezeDoneForSession = false
    /** Main :vpnwatch dispatches [Utility.requestVpnBatchFreeze] at most once per VPN-up session. */
    private var mainVpnBatchFreezeDispatched = false

    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.initialize(applicationContext)
        Log.i(TAG, "onCreate pid=${Process.myPid()} work=${AntiSpyManager.isWorkProfile(this)}")
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        ensureForeground()
        registerVpnCallbacks()
        registerConnectivityReceiver()
        registerFreezeCompleteReceiver()
        vpnPresent = VpnTunnelDetector.isVpnActive(this)
        Log.d(TAG, "initial vpn=$vpnPresent")
        AntiSpyVpnWatchHealth.scheduleWatchdog(this)
        handler.postDelayed(pollRunnable, VPN_POLL_MS)
    }

    private fun pollVpnState() {
        val active = scanVpnActive()
        AntiSpyVpnWatchHealth.recordHeartbeat(this, active)
        ensureMonitoringHooksRegistered()

        if (AntiSpyDummyVpnDisconnector.isSuppressingVpnReactions()) {
            handler.postDelayed(pollRunnable, VPN_POLL_MS)
            return
        }

        if (active != vpnPresent) {
            Log.i(TAG, "poll self-heal: vpnPresent=$vpnPresent active=$active")
            onVpnStateChanged(active)
        } else if (active && shouldAttemptVpnFreeze()) {
            // VPN still up: retry until every auto-freeze app is hidden (unfrozen / new installs).
            handler.post(freezeRunnable)
        }
        handler.postDelayed(pollRunnable, VPN_POLL_MS)
    }

    private fun ensureMonitoringHooksRegistered() {
        if (vpnCallback == null || defaultCallback == null) {
            registerVpnCallbacks()
        }
        if (connectivityReceiver == null) {
            registerConnectivityReceiver()
        }
        if (freezeCompleteReceiver == null) {
            registerFreezeCompleteReceiver()
        }
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
            if (!AntiSpyManager.isWorkProfile(this)) {
                AntiSpyVpnWatchHealth.scheduleFgsRetry(applicationContext)
            }
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

    private fun registerFreezeCompleteReceiver() {
        if (freezeCompleteReceiver != null) return
        freezeCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Utility.ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE) {
                    return
                }
                if (!isMainProfileWatcher()) {
                    val list = AntiSpyManager.getAutoFreezeList(this@AntiSpyVpnWatchService)
                    val visible = WorkProfileBatchFreeze.countStillVisible(
                        this@AntiSpyVpnWatchService,
                        list,
                    )
                    if (visible > 0) {
                        Log.w(
                            TAG,
                            "session-complete ignored: $visible auto-freeze apps still visible",
                        )
                        vpnFreezeDoneForSession = false
                        WorkProfileVpnFreezeCoordinator.resetSession()
                        handler.post(freezeRunnable)
                        return
                    }
                }
                vpnFreezeDoneForSession = true
                WorkProfileVpnFreezeCoordinator.markSessionComplete()
                Log.i(TAG, "VPN batch-freeze session complete")
            }
        }
        try {
            val filter = IntentFilter(Utility.ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(freezeCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(freezeCompleteReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "freeze-complete receiver failed", e)
            freezeCompleteReceiver = null
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
            mainVpnBatchFreezeDispatched = false
            vpnFreezeInFlight = false
            if (!isMainProfileWatcher()) {
                WorkProfileVpnFreezeCoordinator.resetSession()
            }
            AntiSpyVpnPromptManager.onVpnSessionEnded()
            if (isMainProfileWatcher()) {
                postVpnStateAlert(R.string.anti_spy_vpn_alert_disconnected_text)
            }
            return
        }
        // Fresh VPN-up edge: allow batch-freeze even if a prior session was marked complete.
        vpnFreezeDoneForSession = false
        mainVpnBatchFreezeDispatched = false
        if (isMainProfileWatcher()) {
            postVpnStateAlert(R.string.anti_spy_vpn_alert_connected_text)
        } else {
            WorkProfileVpnFreezeCoordinator.resetSession()
        }
        handler.post(freezeRunnable)
    }

    /** While VPN is up, keep trying until no auto-freeze app remains visible in work profile. */
    private fun shouldAttemptVpnFreeze(): Boolean {
        if (!isMainProfileWatcher()) {
            val list = AntiSpyManager.getAutoFreezeList(this)
            val visible = WorkProfileBatchFreeze.countStillVisible(this, list)
            if (visible > 0) {
                if (vpnFreezeDoneForSession) {
                    Log.i(TAG, "retry: $visible visible auto-freeze apps (session was done)")
                    vpnFreezeDoneForSession = false
                    WorkProfileVpnFreezeCoordinator.resetSession()
                }
                return true
            }
        }
        return !vpnFreezeDoneForSession
    }

    private fun isMainProfileWatcher(): Boolean = !AntiSpyManager.isWorkProfile(this)

    private fun postVpnStateAlert(textResId: Int) {
        val title = getString(R.string.anti_spy_monitor_notification_title)
        val text = getString(textResId)
        ZindanToast.show(this, text)
        Utility.postUserAlert(this, VPN_STATE_NOTIFICATION_ID, title, text)
    }

    private fun maybeFreezeAllForVpn() {
        if (vpnFreezeDoneForSession && !shouldAttemptVpnFreeze()) {
            return
        }
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

        if (vpnFreezeInFlight) return
        vpnFreezeInFlight = true
        try {
            if (isMainProfileWatcher()) {
                if (!mainVpnBatchFreezeDispatched) {
                    mainVpnBatchFreezeDispatched = true
                    Utility.requestVpnBatchFreeze(this)
                    Log.i(TAG, "VPN batch-freeze requested from MAIN watcher")
                }
                return
            }

            val list = AntiSpyManager.getAutoFreezeList(this)
            if (list.isEmpty()) {
                Log.w(TAG, "VPN work fallback skipped: auto-freeze list is empty")
            } else {
                requestPublicFreezeAllFromWorkWatcher()
                Log.i(TAG, "VPN work fallback packages: ${list.joinToString(",")}")
                WorkProfileVpnFreezeCoordinator.requestFreeze(this, list, "work-watcher-local")
            }
        } finally {
            handler.postDelayed({ vpnFreezeInFlight = false }, 1500L)
        }
    }

    private fun requestPublicFreezeAllFromWorkWatcher(): Boolean {
        if (mainVpnBatchFreezeDispatched) {
            return false
        }
        mainVpnBatchFreezeDispatched = true
        val publicLaunched = AntiSpyManager.runBatchFreezeAllFromVpn(this)
        Log.i(TAG, "VPN public freeze-all requested from WORK watcher: public=$publicLaunched")
        return publicLaunched
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
        freezeCompleteReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            freezeCompleteReceiver = null
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
        private const val VPN_POLL_MS = 2000L

        fun syncState(context: Context, allowBackgroundRetry: Boolean = true) {
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
                if (allowBackgroundRetry) {
                    AntiSpyVpnWatchHealth.scheduleFgsRetry(app)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "FGS start failed", e)
                if (allowBackgroundRetry) {
                    AntiSpyVpnWatchHealth.scheduleFgsRetry(app)
                }
            } catch (e: RuntimeException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
                ) {
                    Log.w(TAG, "FGS not allowed from background; scheduling retry", e)
                    if (allowBackgroundRetry) {
                        AntiSpyVpnWatchHealth.scheduleFgsRetry(app)
                    }
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
