package net.typeblog.shelter.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import net.typeblog.shelter.services.AntiSpyVpnWatchService
import net.typeblog.shelter.ui.DummyActivity

/**
 * Anti Spy: always active — startup batch freeze, batch freeze on VPN.
 */
object AntiSpyManager {
    private const val TAG = "AntiSpyManager"
    private const val LIST_SYNC_COOLDOWN_MS = 60_000L
    private const val LIST_SYNC_FORCE_MIN_MS = 5_000L
    private const val VPN_WATCH_EVERYWHERE_COOLDOWN_MS = 30_000L

    @Volatile
    private var lastListSyncElapsed = 0L

    @Volatile
    private var lastListSyncHash = 0

    @Volatile
    private var lastVpnWatchEverywhereElapsed = 0L

    const val EXTRA_AUTO_FREEZE_LIST = "auto_freeze_list"
    /** Set when batch-freeze is triggered by VPN-up (scenario 2). */
    const val EXTRA_VPN_AUTO_FREEZE = "anti_spy_vpn_auto_freeze"

    /** How to reach the work profile when freezing the auto-freeze list. */
    enum class AutoFreezeDelivery {
        /** Open [DummyActivity] cross-profile (UI, shortcuts, boot-on-open). */
        FOREGROUND,

        /** DPM in work, or [Utility.scheduleFreezeInWorkProfile] from main (VPN watcher). */
        BACKGROUND
    }

    /** Push auto-freeze list into the work profile. Skips if unchanged within [LIST_SYNC_COOLDOWN_MS]. */
    fun syncAutoFreezeListToWorkProfile(context: Context, force: Boolean = false) {
        if (isWorkProfile(context)) {
            return
        }
        val app = context.applicationContext
        LocalStorageManager.initialize(app)
        if (!LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            return
        }
        val list = getAutoFreezeList(app)
        if (list.isEmpty()) {
            Log.w(TAG, "sync auto-freeze list to work: main list is empty")
            return
        }
        val listHash = list.contentHashCode()
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastListSyncElapsed
        if (listHash == lastListSyncHash) {
            val limit = if (force) LIST_SYNC_FORCE_MIN_MS else LIST_SYNC_COOLDOWN_MS
            if (elapsed < limit) {
                Log.d(TAG, "sync auto-freeze list skipped (unchanged, ${elapsed}ms < ${limit}ms)")
                return
            }
        }
        lastListSyncHash = listHash
        lastListSyncElapsed = now
        try {
            val intent = workSyncIntent(context, list)
            try {
                app.startActivity(intent)
                Log.d(TAG, "sync auto-freeze list to work (${list.size} apps) via activity")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "sync auto-freeze list activity blocked, trying alarm", e)
                if (Utility.scheduleCrossProfileActivity(app, intent, 0xE49E3)) {
                    Log.i(TAG, "sync auto-freeze list scheduled (${list.size} apps)")
                } else {
                    Log.w(TAG, "sync auto-freeze list alarm failed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync auto-freeze list to work failed", e)
        }
    }

    /** Ensure VPN watcher is running; push auto-freeze list at most once per cooldown. */
    fun syncVpnWatchEverywhere(context: Context, forceListSync: Boolean = false) {
        AntiSpyVpnWatchService.syncState(context)
        if (isWorkProfile(context)) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!forceListSync && now - lastVpnWatchEverywhereElapsed < VPN_WATCH_EVERYWHERE_COOLDOWN_MS) {
            Log.d(TAG, "syncVpnWatchEverywhere: list sync skipped (cooldown)")
            return
        }
        lastVpnWatchEverywhereElapsed = now
        syncAutoFreezeListToWorkProfile(context, force = forceListSync)
    }

    private fun workSyncIntent(context: Context, list: Array<String>? = null): Intent {
        val intent = Intent(DummyActivity.SYNC_ANTI_SPY_VPN_WATCH)
        intent.putExtra(EXTRA_AUTO_FREEZE_LIST, list ?: getAutoFreezeList(context))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Utility.transferIntentToProfile(context, intent)
        AuthenticationUtility.signIntent(intent)
        return intent
    }

    fun getAutoFreezeList(): Array<String> = getAutoFreezeList(null)

    fun getAutoFreezeList(context: Context?): Array<String> {
        val list = if (context != null) {
            LocalStorageManager.readStringListFresh(
                context.applicationContext,
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE
            )
        } else {
            LocalStorageManager.getInstance()
                .getStringListFresh(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
        }
        return Utility.normalizeStringList(list)
    }

    /**
     * Freeze every app in the auto-freeze list — same outcome as the snowflake button.
     * Only the delivery path differs ([AutoFreezeDelivery]).
     */
    fun freezeAutoFreezeList(context: Context, delivery: AutoFreezeDelivery) {
        val app = context.applicationContext
        val list = getAutoFreezeList(
            if (delivery == AutoFreezeDelivery.BACKGROUND) app else null
        )
        if (list.isEmpty()) {
            Log.w(TAG, "auto-freeze: list is empty")
            return
        }

        if (isWorkProfile(app)) {
            freezeInWorkProfile(app, list, delivery)
            return
        }

        if (delivery == AutoFreezeDelivery.FOREGROUND) {
            launchPublicFreezeAll(context)
            Utility.scheduleAppListRefresh(context)
            Log.d(TAG, "auto-freeze requested (foreground)")
            return
        }

        if (Utility.startBatchFreezeInWorkProfile(app, list)) {
            Log.i(TAG, "auto-freeze via BatchFreezeService (background), list=${list.size}")
        } else {
            Log.w(TAG, "BatchFreezeService failed, AlarmManager fallback")
            Utility.scheduleFreezeInWorkProfile(app, list)
        }
    }

    private fun freezeInWorkProfile(
        app: Context,
        list: Array<String>,
        delivery: AutoFreezeDelivery
    ) {
        if (delivery == AutoFreezeDelivery.BACKGROUND) {
            val frozen = WorkProfileBatchFreeze.freezeList(app, list)
            Log.i(TAG, "auto-freeze in work (background): $frozen apps")
            if (frozen > 0) {
                Utility.scheduleAppListRefresh(app)
            }
            return
        }
        try {
            val intent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Utility.transferIntentToProfile(app, intent)
            app.startActivity(intent)
            Log.d(TAG, "auto-freeze forwarded to parent (foreground)")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "auto-freeze forward to parent failed", e)
        }
    }

    /** Signed [DummyActivity.PUBLIC_FREEZE_ALL] entry (shortcuts, menu, enable Anti Spy). */
    private fun launchPublicFreezeAll(context: Context) {
        val intent = Intent(DummyActivity.PUBLIC_FREEZE_ALL)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.component = ComponentName(context, DummyActivity::class.java)
        AuthenticationUtility.signIntent(intent)
        context.startActivity(intent)
    }

    /** Snowflake / batch-freeze button, boot-on-open, first launch after update. */
    fun runBatchFreezeAll(context: Context) {
        freezeAutoFreezeList(context, AutoFreezeDelivery.FOREGROUND)
    }

    /**
     * VPN watcher (scenario 2). In the work profile, freeze locally via DPM.
     * From the main profile, cross-profile delivery is blocked without system permissions —
     * the work-profile [AntiSpyVpnWatchService] performs the freeze instead.
     */
    fun freezeAllForVpn(context: Context): Boolean {
        val app = context.applicationContext
        val list = getAutoFreezeList(app)
        if (list.isEmpty()) {
            Log.w(TAG, "auto-freeze on VPN: list is empty")
            return false
        }
        if (isWorkProfile(app)) {
            val frozen = WorkProfileBatchFreeze.freezeList(app, list)
            Log.i(TAG, "auto-freeze on VPN in work: $frozen apps")
            if (frozen > 0) {
                Utility.scheduleAppListRefresh(app)
            }
            return frozen > 0
        }
        Log.d(TAG, "auto-freeze on VPN: main profile — work watcher handles freeze")
        return false
    }

    fun isWorkProfile(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm != null && dpm.isProfileOwnerApp(context.packageName)
    }

    fun onDeviceBoot(storage: LocalStorageManager) {
        scheduleStartupFreeze(storage, "boot")
    }

    fun onApplicationLaunch(storage: LocalStorageManager, versionCode: Int) {
        if (!storage.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            return
        }

        val lastVersion = storage.getInt(LocalStorageManager.PREF_ANTI_SPY_LAUNCH_VERSION_CODE)
        val firstTrackedLaunch = lastVersion == Int.MIN_VALUE
        val firstLaunchAfterUpdate = !firstTrackedLaunch && versionCode > lastVersion

        storage.setInt(LocalStorageManager.PREF_ANTI_SPY_LAUNCH_VERSION_CODE, versionCode)

        if (firstTrackedLaunch || firstLaunchAfterUpdate) {
            scheduleStartupFreeze(
                storage,
                if (firstTrackedLaunch) "first_launch" else "update"
            )
        }
    }

    fun shouldRunStartupFreeze(storage: LocalStorageManager): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_ANTI_SPY_BOOT_FREEZE_PENDING, false)

    fun clearStartupFreezePending(storage: LocalStorageManager) {
        storage.setBoolean(LocalStorageManager.PREF_ANTI_SPY_BOOT_FREEZE_PENDING, false)
    }

    private fun scheduleStartupFreeze(storage: LocalStorageManager, reason: String) {
        storage.setBoolean(LocalStorageManager.PREF_ANTI_SPY_BOOT_FREEZE_PENDING, true)
        Log.d(TAG, "startup freeze scheduled ($reason)")
    }
}
