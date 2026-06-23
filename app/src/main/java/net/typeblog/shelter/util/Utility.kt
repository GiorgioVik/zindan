package net.typeblog.shelter.util

import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.LauncherApps
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.DrawableRes
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.typeblog.shelter.R
import net.typeblog.shelter.receivers.AntiSpyVpnFreezeReceiver
import net.typeblog.shelter.receivers.AppListRefreshReceiver
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver
import net.typeblog.shelter.services.BatchFreezeService
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.ui.AppListFragment
import net.typeblog.shelter.ui.DummyActivity
import net.typeblog.shelter.ui.MainActivity
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

object Utility {
    private const val TAG = "Utility"
    private const val APP_LIST_REFRESH_DELAY_MS = 700L
    private val APP_LIST_REFRESH_FOLLOWUP_DELAYS_MS = longArrayOf(700L, 2000L, 4500L)
    private val APP_LIST_REFRESH_DELIVERY_DELAYS_MS = longArrayOf(50L, 700L, 2000L, 4500L)

    /** [LocalStorageManager.getStringList] yields `[""]` for an empty list. */
    fun normalizeStringList(list: Array<String>?): Array<String> {
        if (list == null || list.isEmpty()) {
            return emptyArray()
        }
        if (list.size == 1 && list[0].isEmpty()) {
            return emptyArray()
        }
        return list
    }

    /**
     * Foreground delivery: [DummyActivity.FREEZE_ALL_IN_LIST] in the work profile.
     */
    fun launchFreezeInWorkProfile(context: Context, list: Array<String>): Boolean {
        val normalized = normalizeStringList(list)
        if (normalized.isEmpty()) {
            return false
        }
        return try {
            val intent = freezeAllInListIntent(normalized)
            transferIntentToProfile(context, intent)
            context.startActivity(intent)
            Log.i(TAG, "launch work-profile freeze for ${normalized.size} apps")
            true
        } catch (e: Exception) {
            Log.w(TAG, "launchFreezeInWorkProfile failed", e)
            false
        }
    }

    /**
     * Background delivery: start [BatchFreezeService] in the work profile (no Activity).
     */
    fun startBatchFreezeInWorkProfile(context: Context, list: Array<String>): Boolean {
        val normalized = normalizeStringList(list)
        if (normalized.isEmpty()) {
            return false
        }
        val intent = BatchFreezeService.buildIntent(context.applicationContext, normalized)
        return startServiceInManagedProfile(context.applicationContext, intent)
    }

    /**
     * Start a service in the managed (work) user from the parent profile.
     */
    fun startServiceInManagedProfile(context: Context, intent: Intent): Boolean {
        val um = context.getSystemService(UserManager::class.java) ?: return false
        val self = Process.myUserHandle()
        for (profile in um.userProfiles) {
            if (profile == self) {
                continue
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val startFgAsUser = Context::class.java.getMethod(
                        "startForegroundServiceAsUser",
                        Intent::class.java,
                        UserHandle::class.java
                    )
                    startFgAsUser.invoke(context, intent, profile)
                    Log.i(TAG, "startForegroundServiceAsUser ok: ${intent.component}")
                    return true
                } catch (e: Exception) {
                    Log.d(TAG, "startForegroundServiceAsUser unavailable, fallback", e)
                }
            }
            try {
                val startAsUser = Context::class.java.getMethod(
                    "startServiceAsUser",
                    Intent::class.java,
                    UserHandle::class.java
                )
                startAsUser.invoke(context, intent, profile)
                Log.i(TAG, "startServiceAsUser ok: ${intent.component}")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "startServiceAsUser unavailable, trying profile context", e)
            }
            try {
                val profileContext = createContextAsUser(context, profile)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    profileContext.startForegroundService(intent)
                } else {
                    profileContext.startService(intent)
                }
                Log.i(TAG, "startService in managed profile ok: ${intent.component}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startService in managed profile failed for $profile", e)
            }
        }
        return false
    }

    @Throws(ReflectiveOperationException::class)
    private fun createContextAsUser(context: Context, user: UserHandle): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val method = Context::class.java.getMethod(
                "createContextAsUser",
                UserHandle::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(context, user, 0) as Context
        } else {
            val method = Context::class.java.getMethod(
                "createPackageContextAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                UserHandle::class.java
            )
            method.invoke(context, context.packageName, 0, user) as Context
        }
    }

    /**
     * Fallback background delivery via `AlarmManager` when cross-profile
     * `startService` is unavailable (may still be blocked on some OEMs).
     */
    fun scheduleFreezeInWorkProfile(context: Context, list: Array<String>) {
        val normalized = normalizeStringList(list)
        if (normalized.isEmpty()) {
            return
        }
        try {
            val intent = freezeAllInListIntent(normalized)
            transferIntentToProfile(context, intent)
            val pi = PendingIntent.getActivity(
                context,
                0xE49E1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(AlarmManager::class.java)
            if (am != null) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 50,
                    pi
                )
                Log.i(TAG, "scheduled work-profile freeze for ${normalized.size} apps")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleFreezeInWorkProfile failed", e)
        }
    }

    /**
     * Work profile → main profile: add a package to the auto-freeze list when
     * [Context.startActivity] from a background receiver is blocked.
     */
    fun scheduleEnableAutoFreezeOnMainProfile(context: Context, packageName: String) {
        if (packageName.isEmpty() || packageName == context.packageName) {
            return
        }
        try {
            val intent = Intent(DummyActivity.ENABLE_AUTO_FREEZE_WORK_PROFILE).apply {
                putExtra("packageName", packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            transferIntentToProfile(context, intent)
            val requestCode = 0xE49E2 xor packageName.hashCode()
            val pi = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(AlarmManager::class.java)
            if (am != null) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 50,
                    pi
                )
                Log.i(TAG, "scheduled enable auto-freeze on main profile for $packageName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleEnableAutoFreezeOnMainProfile failed for $packageName", e)
        }
    }

    /**
     * VPN-up batch freeze using the authoritative main-profile auto-freeze list.
     * Safe from the `:vpnwatch` FGS in either profile; delivery runs in the default app process.
     */
    fun requestVpnBatchFreeze(context: Context) {
        val app = context.applicationContext
        if (AntiSpyManager.isWorkProfile(app)) {
            scheduleVpnBatchFreezeOnMainProfile(app)
            return
        }
        // `:vpnwatch` cannot reliably start cross-profile work from the main process; wake the
        // default app process where [AntiSpyVpnFreezeReceiver] runs.
        scheduleVpnBatchFreezeInAppProcess(app)
        sendVpnBatchFreezeBroadcast(app)
    }

    /** Schedule [AntiSpyVpnFreezeReceiver] in the default app process (same user). */
    private fun scheduleVpnBatchFreezeInAppProcess(context: Context) {
        try {
            val intent = vpnBatchFreezeReceiverIntent(context)
            val pi = PendingIntent.getBroadcast(
                context,
                0xE49E4,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(AlarmManager::class.java)
            if (am != null) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 50,
                    pi
                )
                Log.i(TAG, "scheduled VPN batch freeze in app process")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleVpnBatchFreezeInAppProcess failed", e)
        }
    }

    /** Work profile → main profile when background broadcast is blocked. */
    fun scheduleVpnBatchFreezeOnMainProfile(context: Context) {
        try {
            val intent = vpnBatchFreezeReceiverIntent(context)
            transferIntentToProfile(context, intent)
            val pi = PendingIntent.getBroadcast(
                context,
                0xE49E5,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(AlarmManager::class.java)
            if (am != null) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 50,
                    pi
                )
                Log.i(TAG, "scheduled VPN batch freeze on main profile")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleVpnBatchFreezeOnMainProfile failed", e)
        }
    }

    private fun vpnBatchFreezeReceiverIntent(context: Context): Intent =
        Intent(AntiSpyVpnFreezeReceiver.ACTION).apply {
            setPackage(context.packageName)
            component = ComponentName(context, AntiSpyVpnFreezeReceiver::class.java)
        }

    private fun sendVpnBatchFreezeBroadcast(context: Context) {
        try {
            context.sendBroadcast(vpnBatchFreezeReceiverIntent(context))
            Log.i(TAG, "VPN batch freeze broadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "VPN batch freeze broadcast failed", e)
        }
    }

    private fun freezeAllInListIntent(list: Array<String>): Intent {
        val intent = Intent(DummyActivity.FREEZE_ALL_IN_LIST)
        intent.putExtra("list", list)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return intent
    }

    /** Refresh app lists after a cross-profile freeze/unfreeze DummyActivity finishes. */
    fun scheduleAppListRefresh(
        context: Context,
        delaysMs: LongArray = longArrayOf(APP_LIST_REFRESH_DELAY_MS),
    ) {
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        for (delay in delaysMs) {
            handler.postDelayed({
                LocalBroadcastManager.getInstance(appContext)
                    .sendBroadcast(Intent("net.typeblog.shelter.broadcast.REFRESH"))
            }, delay)
        }
    }

    /**
     * After a background VPN batch-freeze in the work profile: refresh both app-list tabs on the
     * personal profile (with follow-up delays for slow PackageManager updates) and optionally
     * show the success toast there.
     */
    fun notifyBatchFreezeComplete(context: Context, showSuccessToast: Boolean) {
        scheduleAppListRefreshDelivery(context.applicationContext)
        if (showSuccessToast) {
            scheduleShowToastOnMainProfile(context.applicationContext, R.string.freeze_all_success)
        }
    }

    /** Notifies VPN watchers that every auto-freeze app is hidden for this VPN session. */
    fun notifyVpnBatchFreezeSessionComplete(context: Context, showSuccessToast: Boolean) {
        notifyBatchFreezeComplete(context, showSuccessToast)
        deliverVpnBatchFreezeSessionComplete(context.applicationContext)
        if (AntiSpyManager.isWorkProfile(context)) {
            scheduleVpnSessionCompleteOnMainProfile(context.applicationContext)
        }
    }

    /** Broadcast [ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE] in the current user/profile. */
    fun deliverVpnBatchFreezeSessionComplete(context: Context) {
        try {
            val intent = Intent(ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE).apply {
                setPackage(context.packageName)
            }
            context.applicationContext.sendBroadcast(intent)
            Log.i(TAG, "VPN batch-freeze session complete broadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "VPN batch-freeze session complete broadcast failed", e)
        }
    }

    /** Work profile → personal: wake main :vpnwatch via cross-profile Activity (Samsung-safe). */
    private fun scheduleVpnSessionCompleteOnMainProfile(context: Context) {
        try {
            val intent = Intent(DummyActivity.VPN_SESSION_COMPLETE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            transferIntentToProfile(context, intent)
            val pi = PendingIntent.getActivity(
                context,
                0xE49E7,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(AlarmManager::class.java)
            if (am != null) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 50,
                    pi
                )
                Log.i(TAG, "scheduled VPN session complete on main profile")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleVpnSessionCompleteOnMainProfile failed", e)
        }
    }

    const val ACTION_VPN_BATCH_FREEZE_SESSION_COMPLETE =
        "net.typeblog.shelter.action.VPN_BATCH_FREEZE_SESSION_COMPLETE"

    /** Refresh both app-list tabs on the personal profile after work-profile freeze/unfreeze. */
    fun scheduleAppListRefreshOnMainProfile(context: Context) {
        scheduleAppListRefreshDelivery(context.applicationContext)
    }

    /**
     * Store install in the work profile (e.g. RuStore): refresh personal-profile UI lists.
     * Uses Activity + BroadcastReceiver delivery; Samsung may allow only one path.
     */
    fun scheduleAppListRefreshAfterWorkPackageInstall(context: Context) {
        val app = context.applicationContext
        if (AntiSpyManager.isWorkProfile(app)) {
            scheduleAppListRefreshMainActivityOnMainProfile(app)
            scheduleAppListRefreshReceiverOnMainProfile(app)
        }
        scheduleAppListRefreshDelivery(app)
    }

    /** Work profile → personal: wake [MainActivity] to refresh lists (needs manifest intent-filter). */
    private fun scheduleAppListRefreshMainActivityOnMainProfile(context: Context) {
        try {
            val intent = Intent(MainActivity.ACTION_REFRESH_APP_LISTS).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            }
            transferIntentToProfile(context, intent)
            context.startActivity(intent)
            Log.i(TAG, "started MainActivity app-list refresh from work profile")
        } catch (e: Exception) {
            Log.w(TAG, "scheduleAppListRefreshMainActivityOnMainProfile failed", e)
        }
    }

    /** Work profile → personal: run [AppListRefreshReceiver] in the default app process. */
    fun scheduleAppListRefreshReceiverOnMainProfile(context: Context) {
        try {
            val intent = appListRefreshReceiverIntent(context)
            transferIntentToProfile(context, intent)
            val pi = PendingIntent.getBroadcast(
                context,
                0xE49E9,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = context.getSystemService(AlarmManager::class.java)
            if (am != null) {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 50,
                    pi,
                )
                Log.i(TAG, "scheduled app-list refresh receiver on main profile")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scheduleAppListRefreshReceiverOnMainProfile failed", e)
        }
    }

    /**
     * Deliver app-list refresh to the personal profile UI.
     * From the work profile / {@code :vpnwatch}: cross-profile [DummyActivity.REFRESH_MAIN_APP_LIST].
     */
    fun scheduleAppListRefreshDelivery(context: Context) {
        scheduleRefreshMainAppList(context.applicationContext)
    }

    /** Immediate refresh in the personal profile UI process. */
    fun deliverAppListRefreshInMainProcess(context: Context) {
        val app = context.applicationContext
        if (AntiSpyManager.isWorkProfile(app)) {
            scheduleRefreshMainAppList(app)
            return
        }
        MainActivity.refreshIfVisible()
        try {
            LocalBroadcastManager.getInstance(app)
                .sendBroadcast(Intent(AppListFragment.BROADCAST_REFRESH))
        } catch (e: Exception) {
            Log.w(TAG, "local app-list refresh broadcast failed", e)
        }
        try {
            val intent = Intent(app, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REFRESH_APP_LISTS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startActivity app-list refresh failed", e)
        }
    }

    private fun refreshMainAppListIntent(context: Context, fromWork: Boolean): Intent {
        val intent = Intent(DummyActivity.REFRESH_MAIN_APP_LIST).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (fromWork) {
            transferIntentToProfile(context, intent)
        } else {
            intent.setClass(context, DummyActivity::class.java)
        }
        return intent
    }

    private fun scheduleRefreshMainAppList(context: Context) {
        val app = context.applicationContext
        val fromWork = AntiSpyManager.isWorkProfile(app)
        try {
            var requestCode = 0xE49E8
            for (delay in APP_LIST_REFRESH_DELIVERY_DELAYS_MS) {
                val intent = refreshMainAppListIntent(app, fromWork)
                val pi = PendingIntent.getActivity(
                    app,
                    requestCode++,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val am = app.getSystemService(AlarmManager::class.java)
                am?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay,
                    pi,
                )
            }
            Log.i(
                TAG,
                "scheduled personal-profile app-list refresh (work=$fromWork, " +
                    "${APP_LIST_REFRESH_DELIVERY_DELAYS_MS.size} delays)",
            )
        } catch (e: Exception) {
            Log.w(TAG, "scheduleRefreshMainAppList failed", e)
            if (fromWork) {
                scheduleAppListRefreshReceiverOnMainProfile(app)
            } else {
                deliverAppListRefreshInMainProcess(app)
            }
        }
    }

    private fun appListRefreshReceiverIntent(context: Context): Intent =
        Intent(AppListRefreshReceiver.ACTION).apply {
            setPackage(context.packageName)
            component = ComponentName(context, AppListRefreshReceiver::class.java)
        }

    private fun sendAppListRefreshBroadcast(context: Context) {
        if (AntiSpyManager.isWorkProfile(context)) {
            return
        }
        try {
            context.sendBroadcast(appListRefreshReceiverIntent(context))
            Log.i(TAG, "app-list refresh broadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "app-list refresh broadcast failed", e)
        }
    }

    /**
     * Cross-profile delivery of [DummyActivity.SHOW_TOAST] via AlarmManager (toast only).
     */
    private fun scheduleShowToastOnMainProfile(
        context: Context,
        toastResId: Int,
        delaysMs: LongArray = longArrayOf(50L),
    ) {
        val app = context.applicationContext
        val fromWork = AntiSpyManager.isWorkProfile(app)
        try {
            var requestCode = 0xE49E6
            for (delay in delaysMs) {
                val intent = Intent(DummyActivity.SHOW_TOAST).apply {
                    putExtra(MainActivity.EXTRA_TOAST_RES_ID, toastResId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (fromWork) {
                    transferIntentToProfile(app, intent)
                } else {
                    intent.component = ComponentName(app, DummyActivity::class.java)
                }
                val pi = PendingIntent.getActivity(
                    app,
                    requestCode++,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val am = app.getSystemService(AlarmManager::class.java)
                am?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay,
                    pi,
                )
            }
            Log.i(
                TAG,
                "scheduled main-profile refresh/toast (work=$fromWork, toast=$toastResId)",
            )
        } catch (e: Exception) {
            Log.w(TAG, "scheduleShowToastOnMainProfile failed", e)
            if (!fromWork) {
                scheduleAppListRefresh(app, APP_LIST_REFRESH_FOLLOWUP_DELAYS_MS)
            }
        }
    }

    fun showToastOnMainProfile(context: Context, resId: Int) {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isProfileOwnerApp(context.packageName)) {
            ZindanToast.show(context, resId)
            scheduleAppListRefresh(context, APP_LIST_REFRESH_FOLLOWUP_DELAYS_MS)
            return
        }
        scheduleShowToastOnMainProfile(context.applicationContext, resId)
    }

    fun resolveApplicationLabel(context: Context, packageName: String): CharSequence {
        return try {
            val pm = context.packageManager
            var flags = PackageManager.MATCH_DISABLED_COMPONENTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags = flags or PackageManager.MATCH_UNINSTALLED_PACKAGES
            }
            val info = pm.getApplicationInfo(packageName, flags)
            pm.getApplicationLabel(info)
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun isProfileOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isProfileOwnerApp(context.packageName)

    fun stringJoin(delimiter: String, list: Array<String>): String = list.joinToString(delimiter)

    fun transferIntentToProfile(context: Context, intent: Intent) {
        transferIntentToProfileUnsigned(context, intent)
        AuthenticationUtility.signIntent(intent)
    }

    fun transferIntentToProfileUnsigned(context: Context, intent: Intent) {
        val pm = context.packageManager
        val info = pm.queryIntentActivities(intent, 0)
        val match = info.stream()
            .filter { r -> r.activityInfo.packageName != context.packageName }
            .findFirst()
        if (match.isPresent) {
            intent.component = ComponentName(
                match.get().activityInfo.packageName,
                match.get().activityInfo.name
            )
        } else {
            throw IllegalStateException("Cannot find an intent in other profile")
        }
    }

    fun isWorkProfileAvailable(context: Context): Boolean {
        val storage = LocalStorageManager.getInstance()
        val intent = Intent(DummyActivity.TRY_START_SERVICE)
        return try {
            transferIntentToProfileUnsigned(context, intent)
            storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun enforceWorkProfilePolicies(context: Context) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(
            context.applicationContext,
            ShelterDeviceAdminReceiver::class.java
        )

        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.applicationContext, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0
        )

        manager.clearCrossProfileIntentFilters(adminComponent)

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.START_SERVICE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.TRY_START_SERVICE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.UNFREEZE_AND_LAUNCH),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.FREEZE_ALL_IN_LIST),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.UNFREEZE_ALL_IN_LIST),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        // Allow the work profile (profile owner) to surface a toast + refresh on the personal
        // profile after a background batch-freeze (e.g. VPN-triggered auto-freeze). This is a
        // work->personal forward, which on this platform requires FLAG_PARENT_CAN_ACCESS_MANAGED
        // (mirrors the working PUBLIC_FREEZE_ALL/FINALIZE_PROVISION forwards below).
        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.SHOW_TOAST),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.REFRESH_MAIN_APP_LIST),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.REFRESH_MAIN_APP_LIST),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(AppListRefreshReceiver.ACTION),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )
        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(AppListRefreshReceiver.ACTION),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        val mainRefreshFilter = IntentFilter(MainActivity.ACTION_REFRESH_APP_LISTS)
        manager.addCrossProfileIntentFilter(
            adminComponent,
            mainRefreshFilter,
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )
        manager.addCrossProfileIntentFilter(
            adminComponent,
            mainRefreshFilter,
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.ENABLE_AUTO_FREEZE_WORK_PROFILE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.REMOVE_UNFREEZE_SHORTCUT),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.REMOVE_UNFREEZE_SHORTCUT),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.PUBLIC_FREEZE_ALL),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.PUBLIC_UNFREEZE_ALL),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.FINALIZE_PROVISION),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.START_FILE_SHUTTLE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.START_FILE_SHUTTLE_2),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.SYNCHRONIZE_PREFERENCE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        // syncAutoFreezeListToWorkProfile is fired from the personal profile toward the work
        // profile, so it needs the personal->work direction (FLAG_MANAGED_CAN_ACCESS_PARENT on
        // this platform). The previous flag never resolved and spammed IllegalStateException.
        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.SYNC_ANTI_SPY_VPN_WATCH),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.VPN_SESSION_COMPLETE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(BatchFreezeService.ACTION),
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.INSTALL_PACKAGE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        manager.addCrossProfileIntentFilter(
            adminComponent,
            IntentFilter(DummyActivity.UNINSTALL_PACKAGE),
            DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
        )

        val actionSendFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SEND)
            addAction(Intent.ACTION_SEND_MULTIPLE)
            try {
                addDataType("*/*")
            } catch (_: IntentFilter.MalformedMimeTypeException) {
            }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        manager.addCrossProfileIntentFilter(
            adminComponent,
            actionSendFilter,
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        val browsableIntentFilter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("http")
            addDataScheme("https")
        }
        manager.addCrossProfileIntentFilter(
            adminComponent,
            browsableIntentFilter,
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        val browsableDefaultIntentFilter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("http")
            addDataScheme("https")
        }
        manager.addCrossProfileIntentFilter(
            adminComponent,
            browsableDefaultIntentFilter,
            DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
        )

        manager.setCrossProfileContactsSearchDisabled(
            adminComponent,
            SettingsManager.getInstance().getBlockContactsSearchingEnabled()
        )

        manager.setProfileEnabled(adminComponent)
    }

    fun enforceUserRestrictions(context: Context) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(
            context.applicationContext,
            ShelterDeviceAdminReceiver::class.java
        )
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        manager.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            manager.setSecureSetting(
                adminComponent,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                "1"
            )
        }

        manager.addUserRestriction(adminComponent, UserManager.ALLOW_PARENT_PROFILE_APP_LINKING)
    }

    fun isMIUI(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("getprop ro.miui.ui.version.name")
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val line = reader.readLine().trim()
            line.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun drawableToBitmap(drawable: Drawable, maxSizePx: Int = 0): Bitmap {
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap
            return if (maxSizePx > 0) scaleBitmapToMax(bitmap, maxSizePx) else bitmap
        }

        var width = drawable.intrinsicWidth
        width = if (width > 0) width else 1
        var height = drawable.intrinsicHeight
        height = if (height > 0) height else 1

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return if (maxSizePx > 0) scaleBitmapToMax(bitmap, maxSizePx) else bitmap
    }

    /**
     * Pinned shortcut icon: opaque green PNG at shortcut scale (see generate_freeze_icons.ps1).
     * Flatten any stray alpha to green — Samsung shows transparency as white.
     */
    fun createBatchShortcutIcon(context: Context, @DrawableRes shortcutRes: Int): Icon {
        val app = context.applicationContext
        val decoded = BitmapFactory.decodeResource(app.resources, shortcutRes)
            ?: return Icon.createWithResource(app, shortcutRes)
        val opaque = flattenShortcutBitmap(decoded)
        if (opaque !== decoded) {
            decoded.recycle()
        }
        return Icon.createWithBitmap(scaleBitmapToMax(opaque, 512))
    }

    private const val SHORTCUT_ICON_GREEN = 0xFF223D2C.toInt()

    private fun flattenShortcutBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(SHORTCUT_ICON_GREEN)
        canvas.drawBitmap(source, 0f, 0f, null)
        return flat
    }

    private fun scaleBitmapToMax(source: Bitmap, maxSizePx: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxSizePx) {
            return source
        }
        val scale = maxSizePx.toFloat() / largest
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    /** Delete generated files under the app cache directory (safe on cold start after reboot). */
    fun trimApplicationCache(context: Context) {
        val cacheRoot = context.cacheDir ?: return
        try {
            cacheRoot.listFiles()?.forEach { child ->
                child.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "trimApplicationCache failed", e)
        }
    }

    fun killShelterServices(serviceMain: IShelterService, serviceWork: IShelterService) {
        try {
            serviceWork.stopShelterService(true)
        } catch (e: Exception) {
        }

        try {
            serviceMain.stopShelterService(false)
        } catch (e: Exception) {
        }
    }

    fun deleteMissingApps(pref: String, apps: List<ApplicationInfoWrapper>) {
        val list = ArrayList(LocalStorageManager.getInstance().getStringList(pref).toList())
        list.removeIf { item -> apps.none { x -> x.getPackageName() == item } }
        LocalStorageManager.getInstance().setStringList(pref, list.toTypedArray())
    }

    fun buildUnfreezeShortcutLaunchIntent(
        context: Context,
        packageName: String,
        linkedPackages: String? = null
    ): Intent {
        return Intent(DummyActivity.PUBLIC_UNFREEZE_AND_LAUNCH).apply {
            component = ComponentName(context, DummyActivity::class.java)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra("packageName", packageName)
            if (linkedPackages != null) {
                putExtra("linkedPackages", linkedPackages)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun unfreezeShortcutId(packageName: String, linkedPackages: String? = null): String {
        var id = "shelter-$packageName"
        if (linkedPackages != null) {
            id += linkedPackages.hashCode()
        }
        return id
    }

    private data class UnfreezeShortcutRecord(
        val packageName: String,
        val id: String,
        val label: String,
        val linkedPackages: String?
    )

    private const val UNFREEZE_SHORTCUT_FIELD_SEP = "\u001f"

    fun registerUnfreezeShortcut(
        packageName: String,
        id: String,
        label: String,
        linkedPackages: String? = null
    ) {
        val storage = LocalStorageManager.getInstance()
        val list = storage.getStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY)
            .filterNot { entry ->
                val parts = entry.split(UNFREEZE_SHORTCUT_FIELD_SEP)
                parts.size >= 2 && parts[1] == id
            }
            .toMutableList()
        val fields = buildList {
            add(packageName)
            add(id)
            add(label)
            if (!linkedPackages.isNullOrEmpty()) {
                add(linkedPackages)
            }
        }
        list.add(fields.joinToString(UNFREEZE_SHORTCUT_FIELD_SEP))
        storage.setStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY, list.toTypedArray())
    }

    private fun parseUnfreezeShortcutRecord(entry: String): UnfreezeShortcutRecord? {
        val parts = entry.split(UNFREEZE_SHORTCUT_FIELD_SEP)
        if (parts.size < 3) {
            return null
        }
        return UnfreezeShortcutRecord(
            packageName = parts[0],
            id = parts[1],
            label = parts[2],
            linkedPackages = parts.getOrNull(3)
        )
    }

    private fun unfreezeShortcutRecordsForPackage(packageName: String): List<UnfreezeShortcutRecord> {
        return LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY)
            .mapNotNull(::parseUnfreezeShortcutRecord)
            .filter { record ->
                record.packageName == packageName ||
                        record.linkedPackages?.split(",")?.contains(packageName) == true
            }
    }

    private fun unregisterUnfreezeShortcuts(packageName: String) {
        val storage = LocalStorageManager.getInstance()
        val remaining = storage.getStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY)
            .filterNot { entry ->
                val record = parseUnfreezeShortcutRecord(entry) ?: return@filterNot false
                record.packageName == packageName ||
                        record.linkedPackages?.split(",")?.contains(packageName) == true
            }
        storage.setStringList(
            LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY,
            remaining.toTypedArray()
        )
    }

    private fun unfreezeShortcutTargetsPackage(intent: Intent?, packageName: String): Boolean {
        if (intent == null) {
            return false
        }
        val primary = intent.getStringExtra("packageName")
        if (packageName == primary) {
            return true
        }
        val linked = intent.getStringExtra("linkedPackages") ?: return false
        return linked.split(",").any { it == packageName }
    }

    private fun sendUninstallShortcutBroadcast(
        context: Context,
        launchIntent: Intent,
        label: String?
    ) {
        val shortcutIntent = Intent(launchIntent).apply {
            if (categories?.contains(Intent.CATEGORY_DEFAULT) != true) {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        }
        val remove = Intent("com.android.launcher.action.UNINSTALL_SHORTCUT").apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            if (label != null) {
                putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            }
        }
        context.sendBroadcast(remove)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (resolveInfo in context.packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )) {
            context.sendBroadcast(Intent(remove).setPackage(resolveInfo.activityInfo.packageName))
        }
    }

    fun removeUnfreezeLauncherShortcuts(context: Context, packageName: String) {
        val idsToDisable = LinkedHashSet<String>()
        idsToDisable.add(unfreezeShortcutId(packageName))

        val launchIntents = LinkedHashMap<String, Intent>()
        val labels = HashMap<String, String?>()

        for (record in unfreezeShortcutRecordsForPackage(packageName)) {
            idsToDisable.add(record.id)
            val intent = buildUnfreezeShortcutLaunchIntent(
                context,
                record.packageName,
                record.linkedPackages
            )
            launchIntents[intent.toUri(0)] = intent
            labels[intent.toUri(0)] = record.label
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            collectUnfreezeShortcutIds(context, packageName, idsToDisable, launchIntents, labels)
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            try {
                if (idsToDisable.isNotEmpty()) {
                    shortcutManager.disableShortcuts(ArrayList(idsToDisable))
                }
            } catch (e: Exception) {
                Log.w(TAG, "disableShortcuts failed for $packageName", e)
            }
        }

        val baseIntent = buildUnfreezeShortcutLaunchIntent(context, packageName)
        launchIntents.putIfAbsent(baseIntent.toUri(0), baseIntent)

        for ((key, intent) in launchIntents) {
            sendUninstallShortcutBroadcast(context, intent, labels[key])
        }

        unregisterUnfreezeShortcuts(packageName)
    }

    private fun collectUnfreezeShortcutIds(
        context: Context,
        packageName: String,
        idsToDisable: MutableSet<String>,
        launchIntents: MutableMap<String, Intent>,
        labels: MutableMap<String, String?>
    ) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        for (info in shortcutManager.pinnedShortcuts) {
            collectUnfreezeShortcutInfo(info, packageName, idsToDisable, launchIntents, labels)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                val query = LauncherApps.ShortcutQuery().apply {
                    setPackage(context.packageName)
                    setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    )
                }
                val shortcuts = launcherApps.getShortcuts(query, Process.myUserHandle()) ?: emptyList()
                for (info in shortcuts) {
                    collectUnfreezeShortcutInfo(info, packageName, idsToDisable, launchIntents, labels)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LauncherApps shortcut query failed for $packageName", e)
            }
        }
    }

    private fun collectUnfreezeShortcutInfo(
        info: ShortcutInfo,
        packageName: String,
        idsToDisable: MutableSet<String>,
        launchIntents: MutableMap<String, Intent>,
        labels: MutableMap<String, String?>
    ) {
        val matchesId = info.id == unfreezeShortcutId(packageName) ||
                (info.id.startsWith("shelter-$packageName") && info.id.length > "shelter-$packageName".length)
        if (!matchesId && !unfreezeShortcutTargetsPackage(info.intent, packageName)) {
            return
        }
        idsToDisable.add(info.id)
        val intent = info.intent ?: return
        launchIntents[intent.toUri(0)] = intent
        labels.putIfAbsent(intent.toUri(0), info.shortLabel?.toString())
    }

    fun removeUnfreezeLauncherShortcutsEverywhere(context: Context, packageName: String) {
        removeUnfreezeLauncherShortcuts(context.applicationContext, packageName)
        requestRemoveUnfreezeShortcutOnOtherProfile(context.applicationContext, packageName)
    }

    fun requestRemoveUnfreezeShortcutOnOtherProfile(context: Context, packageName: String) {
        try {
            val intent = Intent(DummyActivity.REMOVE_UNFREEZE_SHORTCUT).apply {
                putExtra("packageName", packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            transferIntentToProfile(context, intent)
            context.startActivity(intent)
        } catch (_: IllegalStateException) {
        }
    }

    fun createLauncherShortcut(
        context: Context,
        launchIntent: Intent,
        icon: Icon,
        id: String,
        label: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)

            if (shortcutManager.isRequestPinShortcutSupported) {
                val info = ShortcutInfo.Builder(context, id)
                    .setIntent(launchIntent)
                    .setIcon(icon)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .build()
                val addIntent = shortcutManager.createShortcutResultIntent(info)
                shortcutManager.requestPinShortcut(
                    info,
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        addIntent,
                        PendingIntent.FLAG_IMMUTABLE
                    ).intentSender
                )
            } else {
                ZindanToast.show(
                    context,
                    context.getString(R.string.unsupported_launcher),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
        } else {
            val shortcutIntent = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            shortcutIntent.putExtra(
                Intent.EXTRA_SHORTCUT_ICON,
                drawableToBitmap(icon.loadDrawable(context)!!)
            )
            context.sendBroadcast(shortcutIntent)
            ZindanToast.show(context, R.string.shortcut_create_success)
        }
    }

    fun getMediaStoreId(context: Context, path: String): Int {
        val cursor: Cursor? = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DATA + " LIKE ? ",
            arrayOf(path),
            null
        )
        if (cursor == null || cursor.count == 0) {
            return -1
        } else {
            cursor.moveToFirst()
            return cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID))
        }
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight
                && halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(filePath, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(filePath, options)!!
    }

    fun decodeSampledBitmap(fd: FileDescriptor, reqWidth: Int, reqHeight: Int): Bitmap {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFileDescriptor(fd, null, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFileDescriptor(fd, null, options)!!
    }

    fun getFileExtension(filePath: String): String? {
        val index = filePath.lastIndexOf(".")
        return if (index > 0) {
            filePath.substring(index + 1)
        } else {
            null
        }
    }

    fun checkUsageStatsPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_GET_USAGE_STATS)

    fun checkSystemAlertPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)

    @TargetApi(Build.VERSION_CODES.R)
    fun checkAllFileAccessPermission(): Boolean = Environment.isExternalStorageManager()

    fun checkSpecialAccessPermission(context: Context, name: String): Boolean {
        val appops = context.getSystemService(AppOpsManager::class.java)
        val mode = appops.checkOpNoThrow(name, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Throws(IOException::class)
    fun pipe(`is`: InputStream, os: OutputStream) {
        var n: Int
        val buffer = ByteArray(65536)
        while (`is`.read(buffer).also { n = it } > -1) {
            os.write(buffer, 0, n)
        }
    }

    private const val NOTIFICATION_CHANNEL_ID = "ShelterService"
    private const val NOTIFICATION_CHANNEL_IMPORTANT = "ShelterService-Important"
    private const val NOTIFICATION_CHANNEL_USER_ALERTS = "ShelterUserAlerts"
    private const val VPN_AUTO_FREEZE_SUCCESS_NOTIFICATION_ID = 0xe49d3

    fun postUserAlert(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        icon: Int = R.drawable.ic_lock_open_white_24dp,
    ) {
        val app = context.applicationContext
        app.getSystemService(NotificationManager::class.java).notify(
            notificationId,
            buildUserAlertNotification(app, title, text, icon),
        )
    }

    fun postVpnAutoFreezeSuccessAlert(context: Context) {
        val app = context.applicationContext
        postUserAlert(
            app,
            VPN_AUTO_FREEZE_SUCCESS_NOTIFICATION_ID,
            app.getString(R.string.anti_spy_monitor_notification_title),
            app.getString(R.string.freeze_all_success),
        )
    }

    fun buildUserAlertNotification(
        context: Context,
        title: String,
        text: String,
        icon: Int = R.drawable.ic_lock_open_white_24dp,
    ): Notification {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_USER_ALERTS) == null) {
                val chan = NotificationChannel(
                    NOTIFICATION_CHANNEL_USER_ALERTS,
                    app.getString(R.string.notifications_important),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                chan.enableVibration(true)
                nm.createNotificationChannel(chan)
            }
            return Notification.Builder(app, NOTIFICATION_CHANNEL_USER_ALERTS)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(icon)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
        }
        return Notification.Builder(app)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(icon)
            .setPriority(Notification.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()
    }

    fun buildNotification(
        context: Context,
        ticker: String,
        title: String,
        desc: String,
        icon: Int
    ): Notification = buildNotification(context, false, ticker, title, desc, icon)

    fun buildNotification(
        context: Context,
        important: Boolean,
        ticker: String,
        title: String,
        desc: String,
        icon: Int
    ): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildNotificationOreo(context, important, ticker, title, desc, icon)
        } else {
            buildNotificationLollipop(context, important, ticker, title, desc, icon)
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun buildNotificationLollipop(
        context: Context,
        important: Boolean,
        ticker: String,
        title: String,
        desc: String,
        icon: Int
    ): Notification {
        return Notification.Builder(context)
            .setTicker(ticker)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(icon)
            .setPriority(
                if (important) Notification.PRIORITY_MAX else Notification.PRIORITY_MIN
            )
            .build()
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun buildNotificationOreo(
        context: Context,
        important: Boolean,
        ticker: String,
        title: String,
        desc: String,
        icon: Int
    ): Notification {
        val id = if (important) NOTIFICATION_CHANNEL_IMPORTANT else NOTIFICATION_CHANNEL_ID
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val chan = NotificationChannel(
                id,
                if (important) {
                    context.getString(R.string.notifications_important)
                } else {
                    context.getString(R.string.app_name)
                },
                if (important) {
                    NotificationManager.IMPORTANCE_HIGH
                } else {
                    NotificationManager.IMPORTANCE_MIN
                }
            )
            nm.createNotificationChannel(chan)
        }

        val chan = nm.getNotificationChannel(id)
        if (!important) {
            chan.enableVibration(false)
            chan.enableLights(false)
            chan.importance = NotificationManager.IMPORTANCE_MIN
        } else {
            chan.enableVibration(true)
            chan.importance = NotificationManager.IMPORTANCE_HIGH
        }
        nm.createNotificationChannel(chan)

        return Notification.Builder(context, id)
            .setTicker(ticker)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(icon)
            .build()
    }

    class ActivityResultContractInputWrapper<I, O, T : ActivityResultContract<I, O>>(
        private val inner: T,
        private val input: I
    ) : ActivityResultContract<Void?, O>() {
        @NonNull
        override fun createIntent(@NonNull context: Context, input: Void?): Intent {
            return inner.createIntent(context, this.input)
        }

        override fun parseResult(resultCode: Int, @Nullable intent: Intent?): O {
            return inner.parseResult(resultCode, intent)
        }
    }
}
