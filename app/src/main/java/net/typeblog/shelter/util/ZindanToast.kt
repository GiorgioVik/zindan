package net.typeblog.shelter.util

import android.content.Context
import android.view.Gravity
import android.widget.Toast

object ZindanToast {
    fun show(context: Context, text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(context.applicationContext, text, duration)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, topOffset(context))
        toast.show()
    }

    fun show(context: Context, resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(resId), duration)
    }

    private fun topOffset(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) {
            context.resources.getDimensionPixelSize(resId) + 16
        } else {
            100
        }
    }
}
