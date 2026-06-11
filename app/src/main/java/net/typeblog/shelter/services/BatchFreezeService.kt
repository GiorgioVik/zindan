package net.typeblog.shelter.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.Utility
import net.typeblog.shelter.util.WorkProfileBatchFreeze

/**
 * Runs batch freeze inside the work profile without starting an Activity.
 * Can be started from the main-profile VPN watcher via cross-profile [startService].
 */
class BatchFreezeService : Service() {
    override fun onCreate() {
        super.onCreate()
        LocalStorageManager.initialize(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var list: Array<String>? = null
        if (intent?.hasExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST) == true) {
            list = intent.getStringArrayExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST)
        }
        if (list == null) {
            list = LocalStorageManager.getInstance()
                .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
        }
        list = Utility.normalizeStringList(list)
        Log.i(TAG, "freeze requested pid=${Process.myPid()} list size=${list.size}")
        val frozen = WorkProfileBatchFreeze.freezeList(this, list)
        if (frozen > 0) {
            Utility.scheduleAppListRefresh(this)
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BatchFreezeService"
        const val ACTION = "net.typeblog.shelter.action.BATCH_FREEZE_WORK"

        fun buildIntent(context: Context, list: Array<String>?): Intent {
            val intent = Intent(ACTION)
            intent.setClass(context.applicationContext, BatchFreezeService::class.java)
            if (list != null) {
                intent.putExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST, list)
            }
            return intent
        }
    }
}
