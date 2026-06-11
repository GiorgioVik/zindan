package net.typeblog.shelter.util

import android.app.Activity
import android.content.pm.PackageInstaller
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import net.typeblog.shelter.R

class InstallationProgressListener(
    activity: Activity,
    private val pi: PackageInstaller,
    private val sessionId: Int
) : PackageInstaller.SessionCallback() {
    private val dialog: AlertDialog
    private val progress: ProgressBar

    init {
        val layout = LayoutInflater.from(activity)
            .inflate(
                R.layout.progress_dialog,
                activity.window.decorView as ViewGroup,
                false
            ) as ViewGroup
        progress = layout.findViewById(R.id.progress)

        dialog = AlertDialog.Builder(activity)
            .setCancelable(false)
            .setTitle(R.string.app_installing)
            .setView(layout)
            .create()
        dialog.show()
    }

    override fun onCreated(sessionId: Int) {}

    override fun onBadgingChanged(sessionId: Int) {}

    override fun onActiveChanged(sessionId: Int, active: Boolean) {}

    override fun onProgressChanged(sessionId: Int, progress: Float) {
        this.progress.progress = (progress * 100).toInt()
    }

    override fun onFinished(sessionId: Int, success: Boolean) {
        if (sessionId != this.sessionId) {
            return
        }

        dialog.hide()
        pi.unregisterSessionCallback(this)
    }
}
