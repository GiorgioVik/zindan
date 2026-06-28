package net.typeblog.shelter.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import net.typeblog.shelter.R
import net.typeblog.shelter.receivers.AntiSpyVpnWatchWatchdogReceiver
import net.typeblog.shelter.services.AntiSpyVpnWatchService
import java.util.concurrent.TimeUnit

/**
 * Heartbeat + watchdog for the `:vpnwatch` foreground service (main and work profiles).
 */
object AntiSpyVpnWatchHealth {
    private const val TAG = "VpnWatchHealth"

    /** Poll runs every 2s; allow several missed ticks before declaring stale. */
    const val MAIN_HEARTBEAT_STALE_MS = 12_000L

    /** Work profile heartbeat is mirrored to main at most every 30s. */
    const val WORK_HEARTBEAT_STALE_MS = 90_000L

    const val WATCHDOG_INTERVAL_MS = 30 * 60 * 1000L
    const val FGS_RETRY_DELAY_MS = 5_000L
    private const val WORK_MIRROR_MIN_MS = 30_000L

    const val ACTION_WATCHDOG = "net.typeblog.shelter.action.VPN_WATCH_WATCHDOG"
    const val ACTION_FGS_RETRY = "net.typeblog.shelter.action.VPN_WATCH_FGS_RETRY"
    const val ACTION_HEARTBEAT = "net.typeblog.shelter.action.VPN_WATCH_HEARTBEAT"

    const val EXTRA_VPN_ACTIVE = "vpn_active"
    const val EXTRA_AT = "at"

    @Volatile
    private var lastWorkMirrorElapsedMs = 0L

    fun recordHeartbeat(context: Context, vpnActive: Boolean) {
        val app = context.applicationContext
        LocalStorageManager.initialize(app)
        val storage = LocalStorageManager.getInstance()
        val now = System.currentTimeMillis()
        if (AntiSpyManager.isWorkProfile(app)) {
            storage.setLong(LocalStorageManager.PREF_VPN_WATCH_HEARTBEAT_WORK, now)
            storage.setBoolean(LocalStorageManager.PREF_VPN_WATCH_VPN_WORK, vpnActive)
            maybeMirrorWorkHeartbeatToMain(app, now, vpnActive)
        } else {
            storage.setLong(LocalStorageManager.PREF_VPN_WATCH_HEARTBEAT_MAIN, now)
            storage.setBoolean(LocalStorageManager.PREF_VPN_WATCH_VPN_MAIN, vpnActive)
        }
    }

    fun recordWorkHeartbeatOnMain(at: Long, vpnActive: Boolean) {
        LocalStorageManager.getInstance().apply {
            setLong(LocalStorageManager.PREF_VPN_WATCH_HEARTBEAT_WORK_MIRROR, at)
            setBoolean(LocalStorageManager.PREF_VPN_WATCH_VPN_WORK_MIRROR, vpnActive)
        }
    }

    fun isMainWatcherAlive(context: Context): Boolean =
        ageMs(context, LocalStorageManager.PREF_VPN_WATCH_HEARTBEAT_MAIN) < MAIN_HEARTBEAT_STALE_MS

    fun isWorkWatcherAlive(context: Context): Boolean {
        val age = ageMs(context, LocalStorageManager.PREF_VPN_WATCH_HEARTBEAT_WORK_MIRROR)
        return age in 0 until WORK_HEARTBEAT_STALE_MS
    }

    fun formatStatusLine(context: Context, mainProfile: Boolean): String {
        val storage = LocalStorageManager.getInstance()
        val heartbeatKey = if (mainProfile) {
            LocalStorageManager.PREF_VPN_WATCH_HEARTBEAT_MAIN
        } else {
            LocalStorageManager.PREF_VPN_WATCH_HEARTBEAT_WORK_MIRROR
        }
        val vpnKey = if (mainProfile) {
            LocalStorageManager.PREF_VPN_WATCH_VPN_MAIN
        } else {
            LocalStorageManager.PREF_VPN_WATCH_VPN_WORK_MIRROR
        }
        val staleMs = if (mainProfile) MAIN_HEARTBEAT_STALE_MS else WORK_HEARTBEAT_STALE_MS
        val at = storage.getLong(heartbeatKey, 0L)
        if (at <= 0L) {
            return context.getString(
                if (mainProfile) {
                    R.string.settings_vpn_watch_status_never
                } else {
                    R.string.settings_vpn_watch_status_work_unknown
                },
            )
        }
        val ageMs = System.currentTimeMillis() - at
        if (ageMs >= staleMs) {
            return context.getString(
                R.string.settings_vpn_watch_status_stale,
                formatAge(context, ageMs),
            )
        }
        val vpnText = if (storage.getBoolean(vpnKey, false)) {
            context.getString(R.string.settings_vpn_watch_vpn_on)
        } else {
            context.getString(R.string.settings_vpn_watch_vpn_off)
        }
        return context.getString(
            R.string.settings_vpn_watch_status_ok,
            formatAge(context, ageMs),
            vpnText,
        )
    }

    fun restartMonitoring(context: Context) {
        val app = context.applicationContext
        AntiSpyVpnWatchService.syncState(app, allowBackgroundRetry = false)
        AntiSpyManager.syncVpnWatchEverywhere(app)
        scheduleWatchdog(app)
    }

    fun scheduleWatchdog(context: Context) {
        if (AntiSpyManager.isWorkProfile(context)) {
            return
        }
        val app = context.applicationContext
        if (!LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            return
        }
        try {
            val intent = Intent(app, AntiSpyVpnWatchWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val pi = PendingIntent.getBroadcast(
                app,
                0xE49E8,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            val trigger = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
            Log.d(TAG, "watchdog scheduled in ${WATCHDOG_INTERVAL_MS / 60_000} min")
        } catch (e: Exception) {
            Log.w(TAG, "scheduleWatchdog failed", e)
        }
    }

    fun scheduleFgsRetry(context: Context) {
        if (AntiSpyManager.isWorkProfile(context)) {
            return
        }
        val app = context.applicationContext
        try {
            val intent = Intent(app, AntiSpyVpnWatchWatchdogReceiver::class.java).apply {
                action = ACTION_FGS_RETRY
            }
            val pi = PendingIntent.getBroadcast(
                app,
                0xE49E9,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            val trigger = SystemClock.elapsedRealtime() + FGS_RETRY_DELAY_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
            Log.i(TAG, "FGS retry scheduled in ${FGS_RETRY_DELAY_MS}ms")
        } catch (e: Exception) {
            Log.w(TAG, "scheduleFgsRetry failed", e)
        }
    }

    fun runWatchdog(context: Context) {
        val app = context.applicationContext
        LocalStorageManager.initialize(app)
        if (!LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            return
        }
        if (AntiSpyManager.isWorkProfile(app)) {
            return
        }
        if (!isMainWatcherAlive(app)) {
            Log.w(TAG, "main :vpnwatch stale — restarting")
            AntiSpyVpnWatchService.syncState(app, allowBackgroundRetry = true)
        }
        AntiSpyManager.syncVpnWatchEverywhere(app)
        scheduleWatchdog(app)
    }

    private fun maybeMirrorWorkHeartbeatToMain(context: Context, at: Long, vpnActive: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWorkMirrorElapsedMs < WORK_MIRROR_MIN_MS) {
            return
        }
        lastWorkMirrorElapsedMs = now
        Utility.deliverVpnWatchHeartbeatToMainProfile(context, at, vpnActive)
    }

    private fun ageMs(context: Context, pref: String): Long {
        LocalStorageManager.initialize(context.applicationContext)
        val at = LocalStorageManager.getInstance().getLong(pref, 0L)
        if (at <= 0L) {
            return Long.MAX_VALUE
        }
        return System.currentTimeMillis() - at
    }

    private fun formatAge(context: Context, ageMs: Long): String {
        val sec = TimeUnit.MILLISECONDS.toSeconds(ageMs.coerceAtLeast(0L))
        return when {
            sec < 60 -> context.getString(R.string.settings_vpn_watch_age_seconds, sec.toInt())
            else -> context.getString(
                R.string.settings_vpn_watch_age_minutes,
                (sec / 60).toInt(),
            )
        }
    }
}
