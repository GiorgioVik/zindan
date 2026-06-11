package net.typeblog.shelter.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import net.typeblog.shelter.ui.DummyActivity

/**
 * Helpers for cross-profile launch intents (Anti Spy launch gating is in [AntiSpyLaunchGate]).
 */
object AntiSpyVpnGuard {
    fun resolveWorkAppLabel(context: Context, packageName: String): CharSequence =
        Utility.resolveApplicationLabel(context, packageName)

    fun forwardPublicUnfreezeToParent(activity: Activity, source: Intent): Boolean {
        return try {
            val parentIntent = Intent(DummyActivity.PUBLIC_UNFREEZE_AND_LAUNCH)
            source.extras?.let { parentIntent.putExtras(it) }
            parentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Utility.transferIntentToProfile(activity, parentIntent)
            AuthenticationUtility.signIntent(parentIntent)
            activity.startActivity(parentIntent)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }
}
