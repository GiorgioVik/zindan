package net.typeblog.shelter.services

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import net.typeblog.shelter.R
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver
import net.typeblog.shelter.ui.DummyActivity
import net.typeblog.shelter.util.SettingsManager
import net.typeblog.shelter.util.Utility
import net.typeblog.shelter.util.WorkProfileBatchFreeze
import java.util.Date

// This service simply registers a screen-off listener that will be called when the user
// locks the screen. When this happens, this service will freeze all the apps that the
// user launched through Unfreeze & Launch during the last session.
class FreezeService : Service() {
    private var usageStats: Map<String, UsageStats> = HashMap()
    private var screenLockTime: Long = -1
    private lateinit var alarmManager: AlarmManager
    private var unlockReceiverRegistered = false

    private val freezeWork: AlarmManager.OnAlarmListener = AlarmManager.OnAlarmListener {
        synchronized(FreezeService::class.java) {
            unregisterUnlockReceiverIfNeeded()

            if (appToFreeze.isNotEmpty()) {
                for (app in appToFreeze) {
                    var shouldFreeze = true
                    val stats = usageStats[app]
                    if (stats != null &&
                        screenLockTime - stats.lastTimeUsed <= APP_INACTIVE_TIMEOUT &&
                        stats.totalTimeInForeground >= APP_INACTIVE_TIMEOUT
                    ) {
                        shouldFreeze = false
                    }

                    if (shouldFreeze) {
                        WorkProfileBatchFreeze.freezeOne(this, app)
                    }
                }
                appToFreeze.clear()
            }
            stopSelf()
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            alarmManager.cancel(freezeWork)
            unregisterUnlockReceiverIfNeeded()
        }
    }

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenLockTime = Date().time
            if (SettingsManager.getInstance().getSkipForegroundEnabled() &&
                Utility.checkUsageStatsPermission(this@FreezeService)
            ) {
                val usm = getSystemService(UsageStatsManager::class.java)
                usageStats = usm.queryAndAggregateUsageStats(
                    screenLockTime - APP_INACTIVE_TIMEOUT,
                    screenLockTime,
                )
            }

            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() +
                    SettingsManager.getInstance().getAutoFreezeDelay().toLong() * 1000,
                null,
                freezeWork,
                null,
            )
            registerUnlockReceiverIfNeeded()
        }
    }

    override fun onCreate() {
        super.onCreate()
        alarmManager = getSystemService(AlarmManager::class.java)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        setForeground()
    }

    override fun onDestroy() {
        unregisterUnlockReceiverIfNeeded()
        try {
            unregisterReceiver(lockReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerUnlockReceiverIfNeeded() {
        if (unlockReceiverRegistered) {
            return
        }
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        unlockReceiverRegistered = true
    }

    private fun unregisterUnlockReceiverIfNeeded() {
        if (!unlockReceiverRegistered) {
            return
        }
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: IllegalArgumentException) {
        }
        unlockReceiverRegistered = false
    }

    private fun setForeground() {
        val notification = Utility.buildNotification(
            this,
            getString(R.string.service_auto_freeze_title),
            getString(R.string.service_auto_freeze_title),
            getString(R.string.service_auto_freeze_desc),
            R.drawable.ic_lock_open_white_24dp,
        )

        val intentFreeze = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        Utility.transferIntentToProfileUnsigned(this, intentFreeze)
        notification.actions = arrayOf(
            Notification.Action.Builder(
                null,
                getString(R.string.service_auto_freeze_now),
                PendingIntent.getActivity(
                    this,
                    0,
                    intentFreeze,
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private val appToFreeze = ArrayList<String>()

        private const val APP_INACTIVE_TIMEOUT = 1000L
        private const val NOTIFICATION_ID = 0xe49c0

        @Synchronized
        fun registerAppToFreeze(app: String) {
            if (!appToFreeze.contains(app)) {
                appToFreeze.add(app)
            }
        }

        @Synchronized
        fun hasPendingAppToFreeze(): Boolean = appToFreeze.isNotEmpty()
    }
}
