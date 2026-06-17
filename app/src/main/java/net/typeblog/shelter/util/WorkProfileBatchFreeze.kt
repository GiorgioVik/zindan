package net.typeblog.shelter.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        var reconciled = 0
        for (pkg in normalized) {
            if (pkg.isEmpty()) {
                continue
            }
            try {
                if (dpm.isApplicationHidden(admin, pkg)) {
                    // DPM may report hidden=true while PackageManager still shows the app visible
                    // and running. This desync happens when setApplicationHidden(true) was applied
                    // to a foreground app: the policy was recorded but the hide never landed, and
                    // every later freeze would skip the app forever. Reconcile by forcing a
                    // false -> true toggle so the hide actually takes effect.
                    if (!isResolvable(context, pkg)) {
                        alreadyHidden++
                        continue
                    }
                    Log.w(TAG, "desync: $pkg hidden in DPM but visible in PM, re-applying")
                    dpm.setApplicationHidden(admin, pkg, false)
                    if (dpm.setApplicationHidden(admin, pkg, true)) {
                        reconciled++
                    }
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
            "newly frozen $newlyFrozen, reconciled $reconciled, already hidden $alreadyHidden, of ${normalized.size} packages"
        )
        return newlyFrozen + reconciled
    }

    /**
     * True if the package is currently visible to PackageManager in this (work) profile.
     * A genuinely hidden (frozen) app behaves like uninstalled and throws NameNotFoundException,
     * so a successful lookup means DPM's hidden flag has not actually taken effect.
     */
    private fun isResolvable(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Same list as the snowflake button; delegates to [AntiSpyManager.getAutoFreezeList]. */
    fun freezeAutoFreezeList(context: Context): Int =
        freezeList(context, AntiSpyManager.getAutoFreezeList())
}
