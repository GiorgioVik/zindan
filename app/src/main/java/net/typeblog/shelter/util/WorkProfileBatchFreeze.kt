package net.typeblog.shelter.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver
import net.typeblog.shelter.services.FreezeService

/**
 * Freeze all packages in the auto-freeze list inside the work profile without starting an Activity.
 * Safe to call from a background foreground service (VPN watcher).
 */
object WorkProfileBatchFreeze {
    private const val TAG = "WorkProfileBatchFreeze"

    fun freezeList(context: Context, list: Array<String>): Int {
        if (!AntiSpyManager.isWorkProfile(context)) {
            Log.w(TAG, "freezeList called outside work profile")
            return 0
        }
        var normalized = Utility.normalizeStringList(list)
        if (normalized.isEmpty()) {
            Log.w(TAG, "freezeList: empty list")
            return 0
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return 0
        val admin = ComponentName(context, ShelterDeviceAdminReceiver::class.java)
        var newlyFrozen = 0
        var alreadyHidden = 0
        for (pkg in normalized) {
            if (pkg.isEmpty()) {
                continue
            }
            try {
                if (dpm.isApplicationHidden(admin, pkg)) {
                    alreadyHidden++
                    continue
                }
                if (dpm.setApplicationHidden(admin, pkg, true)) {
                    newlyFrozen++
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to freeze $pkg", e)
            }
        }
        try {
            context.stopService(Intent(context, FreezeService::class.java))
        } catch (_: Exception) {
        }
        Log.i(
            TAG,
            "newly frozen $newlyFrozen, already hidden $alreadyHidden, of ${normalized.size} packages"
        )
        return newlyFrozen
    }

    /** Same list as the snowflake button; delegates to [AntiSpyManager.getAutoFreezeList]. */
    fun freezeAutoFreezeList(context: Context): Int =
        freezeList(context, AntiSpyManager.getAutoFreezeList())
}
