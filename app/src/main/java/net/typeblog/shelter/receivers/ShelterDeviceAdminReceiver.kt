package net.typeblog.shelter.receivers

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import net.typeblog.shelter.R
import net.typeblog.shelter.ui.DummyActivity
import net.typeblog.shelter.util.Utility

class ShelterDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        val i = Intent(context.applicationContext, DummyActivity::class.java)
        i.action = DummyActivity.FINALIZE_PROVISION
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val notification = Utility.buildNotification(
            context,
            true,
            "shelter-finish-provision",
            context.getString(R.string.finish_provision_title),
            context.getString(R.string.finish_provision_desc),
            R.drawable.ic_notification_white_24dp,
        )
        notification.contentIntent = PendingIntent.getActivity(
            context,
            0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 114514
    }
}
