package net.typeblog.shelter.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import net.typeblog.shelter.ui.DummyActivity

/**
 * Auto-freeze list for the work profile (stored in main-profile [LocalStorageManager]).
 * New installs and clones into the work profile are added to the list by default.
 */
object AutoFreezeDefaults {
    fun enableForWorkProfile(packageName: String?) {
        if (packageName.isNullOrEmpty()) {
            return
        }
        val storage = LocalStorageManager.getInstance()
        if (!storage.stringListContains(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
        ) {
            storage.appendStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
        }
    }

    fun enableForWorkProfile(context: Context?, packageName: String?) {
        enableForWorkProfile(packageName)
        if (context != null) {
            AntiSpyManager.syncAutoFreezeListToWorkProfile(context.applicationContext, force = true)
        }
    }

    fun applyDefaultsForNewPackages(
        context: Context?,
        previous: Set<String>?,
        current: Collection<String>?
    ) {
        if (previous == null || current == null) {
            return
        }
        val added = HashSet(current)
        added.removeAll(previous)
        if (added.isEmpty()) {
            return
        }
        for (pkg in added) {
            enableForWorkProfile(pkg)
        }
        if (context != null) {
            AntiSpyManager.syncAutoFreezeListToWorkProfile(context.applicationContext, force = true)
        }
    }

    fun requestEnableOnMainProfile(context: Context, packageName: String?) {
        if (packageName.isNullOrEmpty() || packageName == context.packageName) {
            return
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isProfileOwnerApp(context.packageName)) {
            return
        }
        try {
            val intent = Intent(DummyActivity.ENABLE_AUTO_FREEZE_WORK_PROFILE)
            intent.putExtra("packageName", packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Utility.transferIntentToProfile(context, intent)
            context.startActivity(intent)
        } catch (_: IllegalStateException) {
        }
    }
}
