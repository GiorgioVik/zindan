package net.typeblog.shelter.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.typeblog.shelter.R
import net.typeblog.shelter.util.AntiSpyVpnPromptManager

/**
 * Full-screen dialog over the launcher. Started from the VPN watcher FGS in `:vpnwatch`.
 */
class AntiSpyVpnPromptActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyShowOnTopFlags()
        showDialogForMode(readMode(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (dialog?.isShowing == true) {
            dialog?.dismiss()
        }
        showDialogForMode(readMode(intent))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_VPN_PERMISSION) {
            finish()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun applyShowOnTopFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    private fun showDialogForMode(mode: Int) {
        if (isFinishing) return
        when (mode) {
            AntiSpyVpnPromptManager.MODE_VPN_PERMISSION -> showPermissionDialog()
            AntiSpyVpnPromptManager.MODE_DISPLACEMENT_FAILED -> showFailedDialog()
            else -> showFreezePrompt()
        }
    }

    private fun showFreezePrompt() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.anti_spy_vpn_prompt_title)
            .setMessage(R.string.anti_spy_vpn_prompt_text)
            .setCancelable(false)
            .setPositiveButton(R.string.anti_spy_vpn_prompt_yes) { _, _ ->
                AntiSpyVpnPromptManager.onUserConfirmedFreeze(this)
                finish()
            }
            .setNegativeButton(R.string.anti_spy_vpn_prompt_no) { _, _ ->
                AntiSpyVpnPromptManager.onUserDeclined(this)
                finish()
            }
            .setOnDismissListener {
                if (!isFinishing) {
                    AntiSpyVpnPromptManager.dismiss(this)
                    finish()
                }
            }
            .show()
    }

    private fun showPermissionDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.anti_spy_vpn_prompt_permission_title)
            .setMessage(R.string.anti_spy_vpn_prompt_permission_text)
            .setCancelable(true)
            .setPositiveButton(R.string.anti_spy_vpn_permission_grant) { _, _ ->
                requestVpnPermission()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun showFailedDialog() {
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.anti_spy_vpn_prompt_failed_title)
            .setMessage(R.string.anti_spy_vpn_prompt_failed_text)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun requestVpnPermission() {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(prepare, REQUEST_VPN_PERMISSION)
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 91

        private fun readMode(intent: Intent?): Int {
            if (intent == null) {
                return AntiSpyVpnPromptManager.MODE_FREEZE_PROMPT
            }
            return intent.getIntExtra(
                AntiSpyVpnPromptManager.EXTRA_MODE,
                AntiSpyVpnPromptManager.MODE_FREEZE_PROMPT
            )
        }
    }
}
