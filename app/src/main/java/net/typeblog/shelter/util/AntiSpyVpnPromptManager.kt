package net.typeblog.shelter.util

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import net.typeblog.shelter.ui.AntiSpyVpnPromptActivity
import net.typeblog.shelter.ui.DummyActivity
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen Anti Spy dialogs for scenario 2 (VPN detected externally). */
object AntiSpyVpnPromptManager {
    private const val TAG = "AntiSpyVpnPrompt"

    const val EXTRA_MODE = "anti_spy_vpn_prompt_mode"
    const val MODE_FREEZE_PROMPT = 1
    const val MODE_VPN_PERMISSION = 2
    const val MODE_DISPLACEMENT_FAILED = 3

    private val freezePromptActive = AtomicBoolean(false)
    @Volatile
    private var declinedForCurrentVpnSession = false

    fun isPromptActive(): Boolean = freezePromptActive.get()

    fun isDeclinedForCurrentVpnSession(): Boolean = declinedForCurrentVpnSession

    fun onVpnSessionEnded() {
        declinedForCurrentVpnSession = false
    }

    /** After dummy VPN displaced the external tunnel — do not re-check VPN (tun may linger). */
    fun showFreezePromptAfterDisplacement(context: Context) {
        if (freezePromptActive.getAndSet(true)) {
            return
        }
        scheduleScreenDialog(context, MODE_FREEZE_PROMPT)
    }

    fun showVpnPermissionNeeded(context: Context) {
        scheduleScreenDialog(context, MODE_VPN_PERMISSION)
    }

    fun showDisplacementFailed(context: Context) {
        scheduleScreenDialog(context, MODE_DISPLACEMENT_FAILED)
    }

    /**
     * AlarmManager → Activity bypasses background-activity blocks from the :vpnwatch FGS process.
     */
    private fun scheduleScreenDialog(context: Context, mode: Int) {
        val app = context.applicationContext
        val intent = Intent(app, AntiSpyVpnPromptActivity::class.java)
        intent.putExtra(EXTRA_MODE, mode)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_CLEAR_TOP
                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )

        val requestCode = 0xE49F0 + mode
        val pi = PendingIntent.getActivity(
            app,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = app.getSystemService(AlarmManager::class.java)
        if (am != null) {
            try {
                am.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 50,
                    pi,
                )
                Log.i(TAG, "screen dialog scheduled mode=$mode")
                return
            } catch (e: Exception) {
                Log.w(TAG, "AlarmManager schedule failed mode=$mode", e)
            }
        }
        launchScreenDialogDirect(app, intent, mode)
    }

    private fun launchScreenDialogDirect(context: Context, intent: Intent, mode: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
            Log.i(TAG, "screen dialog launched direct mode=$mode")
        } catch (e: Exception) {
            Log.e(TAG, "launchScreenDialog failed mode=$mode", e)
            if (mode == MODE_FREEZE_PROMPT) {
                freezePromptActive.set(false)
            }
        }
    }

    fun dismiss(@Suppress("UNUSED_PARAMETER") context: Context) {
        freezePromptActive.set(false)
    }

    fun onUserDeclined(@Suppress("UNUSED_PARAMETER") context: Context) {
        declinedForCurrentVpnSession = true
        dismiss(context)
    }

    fun onUserConfirmedFreeze(context: Context) {
        dismiss(context)
        val intent = Intent(context, DummyActivity::class.java)
        intent.action = DummyActivity.PUBLIC_FREEZE_ALL
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.component = android.content.ComponentName(context, DummyActivity::class.java)
        AuthenticationUtility.signIntent(intent)
        context.startActivity(intent)
        Utility.scheduleAppListRefresh(context)
    }
}
