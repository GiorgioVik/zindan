package net.typeblog.shelter.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import net.typeblog.shelter.services.AntiSpyVpnWatchService
import net.typeblog.shelter.ui.DummyActivity

/**
 * Anti Spy: always active — startup batch freeze, batch freeze on VPN.
 */
object AntiSpyManager {
    private const val TAG = "AntiSpyManager"

    const val EXTRA_AUTO_FREEZE_LIST = "auto_freeze_list"

    // Signature of the auto-freeze list last pushed to the work profile in this process.
    // syncAutoFreezeListToWorkProfile launches a (translucent) work-profile activity, which pauses
    // and then resumes MainActivity. Since MainActivity.onResume calls this method again, pushing
    // unconditionally creates an infinite resume -> launch -> resume loop. We only push when the
    // list actually changed, so a repeated onResume with an unchanged list is a no-op.
    private var lastSyncedListSignature: String? = null

    /** How to reach the work profile when freezing the auto-freeze list. */
    enum class AutoFreezeDelivery {
        /** Open [DummyActivity] cross-profile (UI, shortcuts, boot-on-open). */
        FOREGROUND,

        /** DPM in work, or [Utility.scheduleFreezeInWorkProfile] from main (VPN watcher). */
        BACKGROUND
    }

    /** Push auto-freeze list + VPN watcher state into the work profile. */
    fun syncAutoFreezeListToWorkProfile(context: Context) {
        if (isWorkProfile(context)) {
            return
        }
        if (!LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            return
        }
        val list = getAutoFreezeList()
        val signature = list.sorted().joinToString("\u0000")
        // Already pushed this exact list — skip to avoid the resume/launch loop (see field doc).
        if (signature == lastSyncedListSignature) {
            return
        }
        try {
            val intent = workSyncIntent(context, list)
            context.startActivity(intent)
            // Only remember it as synced once the launch succeeded, so a failed forward is retried.
            lastSyncedListSignature = signature
        } catch (e: IllegalStateException) {
            Log.w(TAG, "sync auto-freeze list to work failed", e)
        }
    }

    /** Start/stop VPN watcher in main and work profiles. */
    fun syncVpnWatchEverywhere(context: Context) {
        AntiSpyVpnWatchService.syncState(context)
        if (!isWorkProfile(context)) {
            syncAutoFreezeListToWorkProfile(context)
        }
    }

    private fun workSyncIntent(context: Context, list: Array<String>): Intent {
        val intent = Intent(DummyActivity.SYNC_ANTI_SPY_VPN_WATCH)
        intent.putExtra(EXTRA_AUTO_FREEZE_LIST, list)
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
    fun freezeAutoFreezeList(context: Context, delivery: AutoFreezeDelivery): Int {
        val app = context.applicationContext
        val list = getAutoFreezeList(
            if (delivery == AutoFreezeDelivery.BACKGROUND) app else null
        )
        if (list.isEmpty()) {
            Log.w(TAG, "auto-freeze: list is empty")
            return 0
        }

        if (isWorkProfile(app)) {
            return freezeInWorkProfile(app, list, delivery)
        }

        if (delivery == AutoFreezeDelivery.FOREGROUND) {
            launchPublicFreezeAll(context)
            Utility.scheduleAppListRefresh(context)
            Log.d(TAG, "auto-freeze requested (foreground)")
            return -1
        }

        if (Utility.startBatchFreezeInWorkProfile(app, list)) {
            Log.i(TAG, "auto-freeze via BatchFreezeService (background), list=${list.size}")
        } else {
            Log.w(TAG, "BatchFreezeService failed, AlarmManager fallback")
            Utility.scheduleFreezeInWorkProfile(app, list)
        }
        return -1
    }

    private fun freezeInWorkProfile(
        app: Context,
        list: Array<String>,
        delivery: AutoFreezeDelivery
    ): Int {
        if (delivery == AutoFreezeDelivery.BACKGROUND) {
            val newlyFrozen = WorkProfileBatchFreeze.freezeList(app, list)
            Log.i(TAG, "auto-freeze in work (background): $newlyFrozen apps")
            return newlyFrozen
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
        return -1
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

    /** VPN watcher in the main profile (cross-profile batch freeze in work). */
    fun freezeAllForVpn(context: Context): Int =
        freezeAutoFreezeList(context, AutoFreezeDelivery.BACKGROUND)

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
