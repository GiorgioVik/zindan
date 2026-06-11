package net.typeblog.shelter.ui

import androidx.fragment.app.Fragment

abstract class BaseFragment : Fragment() {
    protected fun runOnUiThread(task: Runnable) {
        activity?.runOnUiThread(task) ?: return
    }
}
