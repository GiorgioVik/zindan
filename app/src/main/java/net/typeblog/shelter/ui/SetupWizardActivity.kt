package net.typeblog.shelter.ui

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.android.setupwizardlib.SetupWizardLayout
import com.android.setupwizardlib.view.NavigationBar
import net.typeblog.shelter.R
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver
import net.typeblog.shelter.util.AuthenticationUtility
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.Utility

class SetupWizardActivity : AppCompatActivity() {
    private var policyManager: DevicePolicyManager? = null
    private var storage: LocalStorageManager? = null

    private val provisionProfile: ActivityResultLauncher<Void?> =
        registerForActivityResult(ProfileProvisionContract(), this::setupProfileCb)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (ACTION_PROFILE_PROVISIONED == intent.action && Utility.isWorkProfileAvailable(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_setup_wizard)
        policyManager = getSystemService(DevicePolicyManager::class.java)
        storage = LocalStorageManager.getInstance()
        supportFragmentManager
            .beginTransaction()
            .replace(
                R.id.setup_wizard_container,
                if (ACTION_RESUME_SETUP == intent.action) {
                    ActionRequiredFragment()
                } else {
                    WelcomeFragment()
                }
            )
            .commit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ACTION_PROFILE_PROVISIONED == intent.action && Utility.isWorkProfileAvailable(this)) {
            finishWithResult(true)
        }
    }

    private fun <T : BaseWizardFragment> switchToFragment(fragment: T, reverseAnimation: Boolean) {
        supportFragmentManager
            .beginTransaction()
            .setCustomAnimations(
                if (reverseAnimation) R.anim.slide_in_from_left else R.anim.slide_in_from_right,
                if (reverseAnimation) R.anim.slide_out_to_right else R.anim.slide_out_to_left
            )
            .replace(R.id.setup_wizard_container, fragment)
            .commit()
    }

    private fun finishWithResult(succeeded: Boolean) {
        setResult(if (succeeded) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun setupProfile() {
        if (!policyManager!!.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            switchToFragment(FailedFragment(), false)
            return
        }

        AuthenticationUtility.reset()

        try {
            provisionProfile.launch(null)
        } catch (_: ActivityNotFoundException) {
            switchToFragment(FailedFragment(), false)
        }
    }

    private fun setupProfileCb(result: Boolean) {
        if (result) {
            if (Utility.isWorkProfileAvailable(this)) {
                finishWithResult(true)
                return
            }

            storage!!.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, true)
            switchToFragment(ActionRequiredFragment(), false)
        } else {
            switchToFragment(FailedFragment(), false)
        }
    }

    class SetupWizardContract : ActivityResultContract<Void?, Boolean>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            return Intent(context, SetupWizardActivity::class.java)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == RESULT_OK
        }
    }

    class ResumeSetupContract : ActivityResultContract<Void?, Boolean>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            return Intent(context, SetupWizardActivity::class.java).apply {
                action = ACTION_RESUME_SETUP
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == RESULT_OK
        }
    }

    private class ProfileProvisionContract : ActivityResultContract<Void?, Boolean>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            val admin = ComponentName(
                context.applicationContext, ShelterDeviceAdminReceiver::class.java
            )
            return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == RESULT_OK
        }
    }

    abstract class BaseWizardFragment : Fragment(), NavigationBar.NavigationBarListener {
        protected var setupActivity: SetupWizardActivity? = null
        protected var wizard: SetupWizardLayout? = null

        protected abstract fun getLayoutResource(): Int

        override fun onNavigateBack() {}

        override fun onNavigateNext() {}

        override fun onAttach(context: Context) {
            super.onAttach(context)
            setupActivity = context as SetupWizardActivity
        }

        override fun onDetach() {
            super.onDetach()
            setupActivity = null
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val view = inflater.inflate(getLayoutResource(), container, false)
            wizard = view.findViewById(R.id.wizard)
            wizard!!.navigationBar.setNavigationBarListener(this)
            wizard!!.setLayoutBackground(
                ContextCompat.getDrawable(inflater.context, R.color.setupWizardHeaderBackground)
            )
            return view
        }

        private fun applyZindanSetupWizardColors() {
            val ctx = requireContext()
            val header = wizard!!.headerTextView
            header?.setTextColor(ContextCompat.getColor(ctx, R.color.setupWizardHeaderText))
            val nav = wizard!!.navigationBar
            nav.setBackgroundColor(ContextCompat.getColor(ctx, R.color.setupWizardHeaderBackground))
            val navTextColor = ContextCompat.getColor(ctx, R.color.setupWizardNavText)
            val navTextColors = ColorStateList.valueOf(navTextColor)
            for (button in arrayOf(nav.backButton, nav.nextButton, nav.moreButton)) {
                button.setTextColor(navTextColors)
                TextViewCompat.setCompoundDrawableTintList(button, navTextColors)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            applyZindanSetupWizardColors()
            ViewCompat.setOnApplyWindowInsetsListener(wizard!!) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

                wizard!!.setDecorPaddingTop(insets.top)

                val nav = wizard!!.navigationBar
                val params = nav.layoutParams
                params.height += insets.bottom

                nav.layoutParams = params

                nav.setPadding(
                    nav.paddingLeft, nav.paddingTop, nav.paddingRight, insets.bottom
                )
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    abstract class TextWizardFragment : BaseWizardFragment() {
        abstract fun getTextRes(): Int

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            view.findViewById<TextView>(R.id.setup_wizard_generic_text).setText(getTextRes())
        }
    }

    class WelcomeFragment : TextWizardFragment() {
        override fun getLayoutResource(): Int = R.layout.fragment_setup_wizard_generic_text

        override fun getTextRes(): Int = R.string.setup_wizard_welcome_text

        override fun onNavigateNext() {
            super.onNavigateNext()
            setupActivity!!.switchToFragment(PermissionsFragment(), false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            wizard!!.setHeaderText(R.string.setup_wizard_welcome)
            wizard!!.navigationBar.backButton.visibility = View.GONE
        }
    }

    class PermissionsFragment : TextWizardFragment() {
        override fun getLayoutResource(): Int = R.layout.fragment_setup_wizard_generic_text

        override fun getTextRes(): Int = R.string.setup_wizard_permissions_text

        override fun onNavigateBack() {
            super.onNavigateBack()
            setupActivity!!.switchToFragment(WelcomeFragment(), true)
        }

        override fun onNavigateNext() {
            super.onNavigateNext()
            setupActivity!!.switchToFragment(CompatibilityFragment(), false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            wizard!!.setHeaderText(R.string.setup_wizard_permissions)
        }
    }

    class CompatibilityFragment : TextWizardFragment() {
        override fun getLayoutResource(): Int = R.layout.fragment_setup_wizard_generic_text

        override fun getTextRes(): Int = R.string.setup_wizard_compatibility_text

        override fun onNavigateBack() {
            super.onNavigateBack()
            setupActivity!!.switchToFragment(PermissionsFragment(), true)
        }

        override fun onNavigateNext() {
            super.onNavigateNext()
            setupActivity!!.switchToFragment(ReadyFragment(), false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            wizard!!.setHeaderText(R.string.setup_wizard_compatibility)
        }
    }

    class ReadyFragment : TextWizardFragment() {
        override fun getLayoutResource(): Int = R.layout.fragment_setup_wizard_generic_text

        override fun getTextRes(): Int = R.string.setup_wizard_ready_text

        override fun onNavigateBack() {
            super.onNavigateBack()
            setupActivity!!.switchToFragment(CompatibilityFragment(), true)
        }

        override fun onNavigateNext() {
            super.onNavigateNext()
            setupActivity!!.switchToFragment(PleaseWaitFragment(), false)
            setupActivity!!.setupProfile()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            wizard!!.setHeaderText(R.string.setup_wizard_ready)
        }
    }

    class PleaseWaitFragment : TextWizardFragment() {
        override fun getLayoutResource(): Int = R.layout.fragment_setup_wizard_generic_text

        override fun getTextRes(): Int = R.string.setup_wizard_please_wait_text

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            wizard!!.setHeaderText(R.string.setup_wizard_please_wait)
            wizard!!.setProgressBarColor(
                view.context.getColorStateList(R.color.setup_wizard_progress_bar)
            )
            wizard!!.isProgressBarShown = true
            wizard!!.navigationBar.backButton.visibility = View.GONE
            wizard!!.navigationBar.nextButton.visibility = View.GONE
        }
    }

    class ActionRequiredFragment : TextWizardFragment() {
        override fun getLayoutResource(): Int = R.layout.fragment_setup_wizard_generic_text

        override fun getTextRes(): Int = R.string.setup_wizard_action_required_text

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            wizard!!.setHeaderText(R.string.setup_wizard_action_required)
            wizard!!.setProgressBarColor(
                view.context.getColorStateList(R.color.setup_wizard_progress_bar)
            )
            wizard!!.isProgressBarShown = true
            wizard!!.navigationBar.backButton.visibility = View.GONE
            wizard!!.navigationBar.nextButton.visibility = View.GONE
        }
    }

    class FailedFragment : TextWizardFragment() {
        override fun getLayoutResource(): Int = R.layout.fragment_setup_wizard_generic_text

        override fun getTextRes(): Int = R.string.setup_wizard_failed_text

        override fun onNavigateNext() {
            super.onNavigateNext()
            setupActivity!!.finishWithResult(false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            wizard!!.setHeaderText(R.string.setup_wizard_failed)
            wizard!!.navigationBar.backButton.visibility = View.GONE
        }
    }

    companion object {
        const val ACTION_RESUME_SETUP = "net.typeblog.shelter.RESUME_SETUP"
        const val ACTION_PROFILE_PROVISIONED = "net.typeblog.shelter.PROFILE_PROVISIONED"
    }
}
