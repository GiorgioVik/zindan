package net.typeblog.shelter.receivers

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.typeblog.shelter.util.AutoFreezeDefaults

/**
 * Work profile only: assign auto-freeze to apps installed outside Zindan (e.g. RuStore).
 */
class WorkProfilePackageAddedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) {
            return
        }
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isProfileOwnerApp(context.packageName)) {
            return
        }
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (packageName == context.packageName) {
            return
        }
        val pendingResult = goAsync()
        try {
            Log.i(TAG, "package added in work profile: $packageName")
            AutoFreezeDefaults.requestEnableOnMainProfile(context, packageName)
        } finally {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "WorkProfilePkgAdded"
    }
}
