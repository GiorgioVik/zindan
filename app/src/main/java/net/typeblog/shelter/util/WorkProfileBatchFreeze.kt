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

    private enum class FreezeOutcome {
        NEWLY,
        RECONCILED,
        ALREADY,
        STILL_VISIBLE,
    }

    fun freezeList(context: Context, list: Array<String>): Int {
        if (!AntiSpyManager.isWorkProfile(context)) {
            Log.w(TAG, "freezeList called outside work profile")
            return 0
        }
        val normalized = Utility.normalizeStringList(list)
        if (normalized.isEmpty()) {
            Log.w(TAG, "freezeList: empty list")
            return 0
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return 0
        val admin = ComponentName(context, ShelterDeviceAdminReceiver::class.java)
        var newlyFrozen = 0
        var alreadyHidden = 0
        var reconciled = 0
        var stillVisible = 0
        for (pkg in normalized) {
            if (pkg.isEmpty()) {
                continue
            }
            try {
                when (freezePackage(dpm, admin, context, pkg)) {
                    FreezeOutcome.NEWLY -> newlyFrozen++
                    FreezeOutcome.RECONCILED -> reconciled++
                    FreezeOutcome.ALREADY -> alreadyHidden++
                    FreezeOutcome.STILL_VISIBLE -> stillVisible++
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to freeze $pkg", e)
                if (isResolvable(context, pkg)) {
                    stillVisible++
                }
            }
        }
        try {
            context.stopService(Intent(context, FreezeService::class.java))
        } catch (_: Exception) {
        }
        Log.i(
            TAG,
            "newly frozen $newlyFrozen, reconciled $reconciled, already hidden $alreadyHidden, " +
                "still visible $stillVisible, of ${normalized.size} packages"
        )
        return newlyFrozen + reconciled
    }

    /** Packages in [list] that PackageManager can still resolve (not actually hidden). */
    fun countStillVisible(context: Context, list: Array<String>): Int {
        if (!AntiSpyManager.isWorkProfile(context)) {
            return 0
        }
        return Utility.normalizeStringList(list).count { pkg ->
            pkg.isNotEmpty() && isResolvable(context, pkg)
        }
    }

    private fun freezePackage(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
        pkg: String,
    ): FreezeOutcome {
        if (!isResolvable(context, pkg)) {
            return FreezeOutcome.ALREADY
        }
        if (dpm.isApplicationHidden(admin, pkg)) {
            Log.w(TAG, "desync: $pkg hidden in DPM but visible in PM, re-applying")
            return if (reconcileHide(dpm, admin, context, pkg)) {
                FreezeOutcome.RECONCILED
            } else {
                FreezeOutcome.STILL_VISIBLE
            }
        }
        if (dpm.setApplicationHidden(admin, pkg, true) && !isResolvable(context, pkg)) {
            return FreezeOutcome.NEWLY
        }
        Log.w(TAG, "hide failed or incomplete for foreground $pkg, toggling hidden")
        return if (reconcileHide(dpm, admin, context, pkg)) {
            FreezeOutcome.RECONCILED
        } else {
            FreezeOutcome.STILL_VISIBLE
        }
    }

    private fun reconcileHide(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
        pkg: String,
    ): Boolean {
        dpm.setApplicationHidden(admin, pkg, false)
        val ok = dpm.setApplicationHidden(admin, pkg, true)
        if (!ok || isResolvable(context, pkg)) {
            Log.w(TAG, "reconcile incomplete for $pkg (dpm=$ok, pm visible=${isResolvable(context, pkg)})")
            return false
        }
        return true
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
