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
 * Freeze packages inside the work profile without starting an Activity.
 * Verifies hide via PackageManager and reconciles DPM/PM desync.
 */
object WorkProfileBatchFreeze {
    private const val TAG = "WorkProfileBatchFreeze"

    enum class FreezeStatus {
        NEWLY_FROZEN,
        ALREADY_HIDDEN,
        RECONCILED,
        STILL_VISIBLE_FOREGROUND,
        FAILED_POLICY,
        PACKAGE_MISSING,
    }

    data class FreezePackageResult(
        val packageName: String,
        val status: FreezeStatus,
    )

    data class BatchFreezeResult(
        val packages: List<FreezePackageResult>,
    ) {
        val newlyFrozenCount: Int =
            packages.count {
                it.status == FreezeStatus.NEWLY_FROZEN || it.status == FreezeStatus.RECONCILED
            }

        val stillVisiblePackages: List<String> =
            packages.filter { it.status == FreezeStatus.STILL_VISIBLE_FOREGROUND }
                .map { it.packageName }

        val allHidden: Boolean = stillVisiblePackages.isEmpty()
    }

    private enum class FreezeOutcome {
        NEWLY,
        RECONCILED,
        ALREADY,
        STILL_VISIBLE,
        PACKAGE_MISSING,
    }

    fun freezeOne(context: Context, pkg: String): FreezePackageResult {
        if (!AntiSpyManager.isWorkProfile(context)) {
            return FreezePackageResult(pkg, FreezeStatus.FAILED_POLICY)
        }
        if (pkg.isEmpty()) {
            return FreezePackageResult(pkg, FreezeStatus.PACKAGE_MISSING)
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return FreezePackageResult(pkg, FreezeStatus.FAILED_POLICY)
        val admin = ComponentName(context, ShelterDeviceAdminReceiver::class.java)
        return try {
            toPublicResult(pkg, freezePackage(dpm, admin, context, pkg))
        } catch (e: Exception) {
            Log.w(TAG, "freezeOne failed for $pkg", e)
            if (isResolvable(context, pkg)) {
                FreezePackageResult(pkg, FreezeStatus.STILL_VISIBLE_FOREGROUND)
            } else {
                FreezePackageResult(pkg, FreezeStatus.FAILED_POLICY)
            }
        }
    }

    fun freezeList(context: Context, list: Array<String>): Int {
        val result = freezeListWithResult(context, list)
        persistLastBatchResult(context, result)
        return result.newlyFrozenCount
    }

    fun freezeListWithResult(context: Context, list: Array<String>): BatchFreezeResult {
        if (!AntiSpyManager.isWorkProfile(context)) {
            Log.w(TAG, "freezeList called outside work profile")
            return BatchFreezeResult(emptyList())
        }
        val normalized = Utility.normalizeStringList(list)
        if (normalized.isEmpty()) {
            Log.w(TAG, "freezeList: empty list")
            return BatchFreezeResult(emptyList())
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return BatchFreezeResult(emptyList())
        val admin = ComponentName(context, ShelterDeviceAdminReceiver::class.java)
        val results = ArrayList<FreezePackageResult>(normalized.size)
        var newlyFrozen = 0
        var reconciled = 0
        var alreadyHidden = 0
        var stillVisible = 0
        var missing = 0
        for (pkg in normalized) {
            if (pkg.isEmpty()) {
                continue
            }
            try {
                val outcome = freezePackage(dpm, admin, context, pkg)
                val public = toPublicResult(pkg, outcome)
                results.add(public)
                when (outcome) {
                    FreezeOutcome.NEWLY -> newlyFrozen++
                    FreezeOutcome.RECONCILED -> reconciled++
                    FreezeOutcome.ALREADY -> alreadyHidden++
                    FreezeOutcome.STILL_VISIBLE -> stillVisible++
                    FreezeOutcome.PACKAGE_MISSING -> missing++
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to freeze $pkg", e)
                if (isResolvable(context, pkg)) {
                    stillVisible++
                    results.add(FreezePackageResult(pkg, FreezeStatus.STILL_VISIBLE_FOREGROUND))
                } else {
                    results.add(FreezePackageResult(pkg, FreezeStatus.FAILED_POLICY))
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
                "still visible $stillVisible, missing $missing, of ${normalized.size} packages"
        )
        return BatchFreezeResult(results)
    }

    fun persistLastBatchResult(context: Context, result: BatchFreezeResult) {
        val storage = LocalStorageManager.getInstance()
        storage.setLong(LocalStorageManager.PREF_LAST_BATCH_FREEZE_AT, System.currentTimeMillis())
        storage.setInt(LocalStorageManager.PREF_LAST_BATCH_FREEZE_NEWLY, result.newlyFrozenCount)
        storage.setInt(
            LocalStorageManager.PREF_LAST_BATCH_FREEZE_STILL_VISIBLE,
            result.stillVisiblePackages.size,
        )
        storage.setStringList(
            LocalStorageManager.PREF_LAST_BATCH_FREEZE_STILL_VISIBLE_PKGS,
            result.stillVisiblePackages.toTypedArray(),
        )
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

    private fun toPublicResult(pkg: String, outcome: FreezeOutcome): FreezePackageResult =
        FreezePackageResult(
            pkg,
            when (outcome) {
                FreezeOutcome.NEWLY -> FreezeStatus.NEWLY_FROZEN
                FreezeOutcome.RECONCILED -> FreezeStatus.RECONCILED
                FreezeOutcome.ALREADY -> FreezeStatus.ALREADY_HIDDEN
                FreezeOutcome.STILL_VISIBLE -> FreezeStatus.STILL_VISIBLE_FOREGROUND
                FreezeOutcome.PACKAGE_MISSING -> FreezeStatus.PACKAGE_MISSING
            },
        )

    private fun freezePackage(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
        pkg: String,
    ): FreezeOutcome {
        if (!isInstalled(context, pkg)) {
            return FreezeOutcome.PACKAGE_MISSING
        }
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
            Log.w(
                TAG,
                "reconcile incomplete for $pkg (dpm=$ok, pm visible=${isResolvable(context, pkg)})",
            )
            return false
        }
        return true
    }

    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * True if the package is currently visible to PackageManager in this (work) profile.
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
