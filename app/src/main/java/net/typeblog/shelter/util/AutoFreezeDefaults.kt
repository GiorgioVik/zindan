package net.typeblog.shelter.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.util.Log
import net.typeblog.shelter.ui.DummyActivity

/**
 * Auto-freeze list for the work profile (stored in main-profile [LocalStorageManager]).
 * Store installs and Zindan clones are added by default; user opt-out is persistent.
 */
object AutoFreezeDefaults {
    fun enableForWorkProfile(packageName: String?, clearOptOut: Boolean = false) {
        if (packageName.isNullOrEmpty()) {
            return
        }
        val storage = LocalStorageManager.getInstance()
        if (clearOptOut) {
            removeOptOut(packageName)
        } else if (isOptedOut(packageName)) {
            return
        }
        if (!storage.stringListContains(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
        ) {
            storage.appendStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
            AntiSpyManager.invalidateAutoFreezeListSync()
        }
        clearPendingStoreAutoFreeze(packageName)
    }

    fun enableForWorkProfile(context: Context?, packageName: String?, clearOptOut: Boolean = false) {
        enableForWorkProfile(packageName, clearOptOut)
        if (context != null) {
            AntiSpyManager.syncAutoFreezeListToWorkProfile(context.applicationContext)
        }
    }

    fun optOutOfAutoFreeze(packageName: String) {
        if (packageName.isEmpty()) {
            return
        }
        val storage = LocalStorageManager.getInstance()
        if (!storage.stringListContains(
                LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
                packageName
            )
        ) {
            storage.appendStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
                packageName
            )
        }
        clearPendingStoreAutoFreeze(packageName)
    }

    fun isOptedOut(packageName: String): Boolean =
        LocalStorageManager.getInstance().stringListContains(
            LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
            packageName
        )

    fun removeOptOut(packageName: String) {
        LocalStorageManager.getInstance().removeFromStringList(
            LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
            packageName
        )
    }

    fun clearWorkProfilePackageTracking(packageName: String) {
        val storage = LocalStorageManager.getInstance()
        storage.removeFromStringList(
            LocalStorageManager.PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE,
            packageName
        )
        clearPendingStoreAutoFreeze(packageName)
    }

    /** Store install queued on the main profile until the auto-freeze list can be updated. */
    fun markPendingStoreAutoFreeze(context: Context, packageName: String) {
        if (packageName.isEmpty() || AntiSpyManager.isWorkProfile(context)) {
            return
        }
        if (isOptedOut(packageName)) {
            return
        }
        val storage = LocalStorageManager.getInstance()
        if (!storage.stringListContains(
                LocalStorageManager.PREF_PENDING_STORE_AUTO_FREEZE,
                packageName
            )
        ) {
            storage.appendStringList(
                LocalStorageManager.PREF_PENDING_STORE_AUTO_FREEZE,
                packageName
            )
        }
    }

    fun clearPendingStoreAutoFreeze(packageName: String) {
        LocalStorageManager.getInstance().removeFromStringList(
            LocalStorageManager.PREF_PENDING_STORE_AUTO_FREEZE,
            packageName
        )
    }

    /**
     * Consume packages queued by an explicit install event (store receiver or work-profile
     * installer). Also picks up new packages vs the persisted baseline when cross-profile
     * delivery failed (e.g. RuStore install with Zindan closed).
     */
    fun applyDefaultsForNewPackages(
        context: Context?,
        previous: Set<String>?,
        current: Collection<String>?
    ) {
        if (current == null) {
            return
        }
        val storage = LocalStorageManager.getInstance()
        val currentSet = current.toHashSet()
        var changed = false

        val pending = storage.getStringList(
            LocalStorageManager.PREF_PENDING_STORE_AUTO_FREEZE
        ).toHashSet()
        for (pkg in pending) {
            if (context != null && pkg == context.packageName) {
                clearPendingStoreAutoFreeze(pkg)
                continue
            }
            if (pkg !in currentSet) {
                continue
            }
            if (isOptedOut(pkg)) {
                clearPendingStoreAutoFreeze(pkg)
                continue
            }
            val before = storage.stringListContains(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                pkg
            )
            enableForWorkProfile(pkg, clearOptOut = false)
            if (!before) {
                changed = true
            }
        }

        val known = storage.getStringList(
            LocalStorageManager.PREF_KNOWN_WORK_PROFILE_PACKAGES
        ).toHashSet()
        if (known.isEmpty()) {
            storage.setStringList(
                LocalStorageManager.PREF_KNOWN_WORK_PROFILE_PACKAGES,
                currentSet.toTypedArray()
            )
            if (changed && context != null) {
                AntiSpyManager.syncAutoFreezeListToWorkProfile(context.applicationContext)
            }
            return
        }

        val sessionPrev = previous ?: known
        val added = HashSet(currentSet)
        added.removeAll(sessionPrev)
        for (pkg in added) {
            if (context != null && pkg == context.packageName) {
                continue
            }
            if (isOptedOut(pkg)) {
                continue
            }
            val before = storage.stringListContains(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                pkg
            )
            enableForWorkProfile(pkg, clearOptOut = false)
            if (!before) {
                changed = true
            }
        }

        storage.setStringList(
            LocalStorageManager.PREF_KNOWN_WORK_PROFILE_PACKAGES,
            currentSet.toTypedArray()
        )
        if (changed && context != null) {
            AntiSpyManager.syncAutoFreezeListToWorkProfile(context.applicationContext)
        }
    }

    fun requestEnableOnMainProfile(context: Context, packageName: String?) {
        if (packageName.isNullOrEmpty() || packageName == context.packageName) {
            return
        }
        if (isOptedOut(packageName)) {
            return
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isProfileOwnerApp(context.packageName)) {
            return
        }
        try {
            val intent = enableAutoFreezeIntent(packageName)
            Utility.transferIntentToProfile(context, intent)
            context.startActivity(intent)
            Log.i(TAG, "requested auto-freeze for $packageName on main profile")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startActivity enable auto-freeze failed for $packageName", e)
            Utility.scheduleEnableAutoFreezeOnMainProfile(context, packageName)
        } catch (e: Exception) {
            Log.w(TAG, "enable auto-freeze on main profile failed for $packageName", e)
            Utility.scheduleEnableAutoFreezeOnMainProfile(context, packageName)
        }
    }

    private fun enableAutoFreezeIntent(packageName: String): Intent =
        Intent(DummyActivity.ENABLE_AUTO_FREEZE_WORK_PROFILE).apply {
            putExtra("packageName", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    private const val TAG = "AutoFreezeDefaults"
}
