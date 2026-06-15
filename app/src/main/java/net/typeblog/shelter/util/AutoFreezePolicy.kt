package net.typeblog.shelter.util

import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.util.ApplicationInfoWrapper

object AutoFreezePolicy {
    fun isInAutoFreezeList(packageName: String): Boolean =
        LocalStorageManager.getInstance().stringListContains(
            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
            packageName
        )

    /**
     * VPN gate applies to auto-freeze apps, batch unfreeze (null/empty package), and forced paths
     * such as clone into the work profile.
     */
    fun shouldApplyVpnGate(packageName: String?, forceGate: Boolean = false): Boolean {
        if (forceGate) return true
        if (packageName.isNullOrEmpty()) return true
        return isInAutoFreezeList(packageName)
    }

    fun migrateLegacyFrozenWithoutAutoFreeze(
        service: IShelterService,
        apps: List<ApplicationInfoWrapper>
    ) {
        val storage = LocalStorageManager.getInstance()
        if (storage.getBoolean(LocalStorageManager.PREF_LEGACY_FROZEN_MIGRATION_DONE)) {
            return
        }
        storage.setBoolean(LocalStorageManager.PREF_LEGACY_FROZEN_MIGRATION_DONE, true)
        for (app in apps) {
            if (!app.isHidden()) continue
            if (isInAutoFreezeList(app.getPackageName())) continue
            try {
                service.unfreezeApp(app)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Work profile list order: frozen → unfrozen with auto-freeze (snowflake) → rest; A–Z within tier.
     */
    fun sortWorkProfileApps(
        apps: MutableList<ApplicationInfoWrapper>,
        autoFreezePackages: Set<String>
    ) {
        apps.sortWith { x, y ->
            val tierCompare = workProfileSortTier(x, autoFreezePackages)
                .compareTo(workProfileSortTier(y, autoFreezePackages))
            if (tierCompare != 0) {
                return@sortWith tierCompare
            }
            x.getLabel()!!.compareTo(y.getLabel()!!, ignoreCase = true)
        }
    }

    private fun workProfileSortTier(app: ApplicationInfoWrapper, autoFreezePackages: Set<String>): Int =
        when {
            app.isHidden() -> 0
            autoFreezePackages.contains(app.getPackageName()) -> 1
            else -> 2
        }
}

