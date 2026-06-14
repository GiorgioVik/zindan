package net.typeblog.shelter.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.widget.Toast

object ZindanToast {
    const val DISPLAY_MS = 6000

    fun show(context: Context, text: CharSequence, durationMs: Int = DISPLAY_MS) {
        val anchor = findToastContext(context)
        val toast = Toast.makeText(anchor, text, Toast.LENGTH_LONG)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, topOffset(anchor))
        applyDuration(toast, durationMs)
        toast.show()
    }

    fun show(context: Context, resId: Int, durationMs: Int = DISPLAY_MS) {
        show(context, context.getString(resId), durationMs)
    }

    private fun findToastContext(context: Context): Context {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity && !current.isFinishing) {
                return current
            }
            current = current.baseContext
        }
        return context.applicationContext
    }

    private fun topOffset(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            context.resources.getDimensionPixelSize(resId) + 16
        } else {
            100
        }
    }

    private fun applyDuration(toast: Toast, durationMs: Int) {
        try {
            val tnField = Toast::class.java.getDeclaredField("mTN")
            tnField.isAccessible = true
            val tn = tnField.get(toast)
            val durationField = tn.javaClass.getDeclaredField("mDuration")
            durationField.isAccessible = true
            durationField.setInt(tn, durationMs)
        } catch (_: Exception) {
        }
    }
}

