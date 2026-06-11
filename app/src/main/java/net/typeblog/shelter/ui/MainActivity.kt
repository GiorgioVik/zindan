package net.typeblog.shelter.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.text.TextUtils
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import net.typeblog.shelter.BuildConfig
import net.typeblog.shelter.R
import net.typeblog.shelter.ShelterApplication
import net.typeblog.shelter.services.IAppInstallCallback
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.services.IStartActivityProxy
import net.typeblog.shelter.services.KillerService
import net.typeblog.shelter.util.AntiSpyLaunchGate
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.SettingsManager
import net.typeblog.shelter.util.UriForwardProxy
import net.typeblog.shelter.util.Utility
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val startSetup =
        registerForActivityResult(SetupWizardActivity.SetupWizardContract(), this::setupWizardCb)
    private val resumeSetup =
        registerForActivityResult(SetupWizardActivity.ResumeSetupContract(), this::setupWizardCb)
    private val selectApk =
        registerForActivityResult(
            Utility.ActivityResultContractInputWrapper(
                ActivityResultContracts.OpenDocument(),
                arrayOf("application/vnd.android.package-archive")
            ),
            this::onApkSelected
        )
    private val tryStartWorkService =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::tryStartWorkServiceCb)
    private val bindWorkService =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), this::bindWorkServiceCb)
    private val antiSpyVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                retryPendingLaunchAfterVpnPermission()
            } else {
                showAntiSpyVpnLaunchBlockedDialog(AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED)
            }
        }
    private val postNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                AntiSpyManager.syncVpnWatchEverywhere(this)
            }
        }

    private var storage: LocalStorageManager? = null
    private var restarting = false
    private var serviceMain: IShelterService? = null
    private var serviceWork: IShelterService? = null
    var showAll = false
    private var pendingVpnBlockReason = 0
    private var pendingLaunchPackageName: String? = null

    private val antiSpyVpnBlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = intent.getIntExtra(
                AntiSpyLaunchGate.EXTRA_BLOCK_REASON,
                AntiSpyLaunchGate.REASON_VPN_STILL_ACTIVE
            )
            val packageName = intent.getStringExtra(AntiSpyLaunchGate.EXTRA_PACKAGE_NAME)
            if (!TextUtils.isEmpty(packageName)) {
                pendingLaunchPackageName = packageName
            }
            pendingVpnBlockReason = reason
            showAntiSpyVpnLaunchBlockedDialog(reason)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.main_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayUseLogoEnabled(true)
            setLogo(R.drawable.ic_toolbar_wordmark)
        }
        val logoInset = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()
        toolbar.setContentInsetsRelative(logoInset, 4)
        storage = LocalStorageManager.getInstance()

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(
                antiSpyVpnBlockReceiver,
                IntentFilter(AntiSpyLaunchGate.BROADCAST_LAUNCH_BLOCKED_VPN)
            )

        if (getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(packageName)) {
            android.util.Log.d("MainActivity", "started in user profile. stopping.")
            finish()
        } else {
            init()
        }
    }

    private fun init() {
        val s = storage!!
        if (s.getBoolean(LocalStorageManager.PREF_IS_SETTING_UP) && !Utility.isWorkProfileAvailable(this)) {
            resumeSetup.launch(null)
        } else if (!s.getBoolean(LocalStorageManager.PREF_HAS_SETUP)) {
            startSetup.launch(null)
        } else {
            AntiSpyManager.onApplicationLaunch(s, BuildConfig.VERSION_CODE)
            requestAntiSpyNotificationPermissionIfNeeded()
            SettingsManager.getInstance().applyAll()
            bindServices()
        }
    }

    private fun setupWizardCb(result: Boolean) {
        if (result) init() else finish()
    }

    private fun bindServices() {
        (application as ShelterApplication).bindShelterService(object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                serviceMain = IShelterService.Stub.asInterface(service)
                tryStartWorkService()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // dummy
            }
        }, false)
    }

    private fun tryStartWorkService() {
        val intent = Intent(DummyActivity.TRY_START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            Utility.transferIntentToProfile(this, intent)
        } catch (_: IllegalStateException) {
            storage!!.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
            Toast.makeText(this, getString(R.string.work_profile_not_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tryStartWorkService.launch(intent)
    }

    private fun tryStartWorkServiceCb(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            bindWorkService()
        } else {
            Toast.makeText(this, getString(R.string.work_mode_disabled), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun bindWorkService() {
        val intent = Intent(DummyActivity.START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        Utility.transferIntentToProfile(this, intent)
        bindWorkService.launch(intent)
    }

    private fun bindWorkServiceCb(result: ActivityResult) {
        if (result.resultCode == RESULT_OK && result.data != null) {
            val extra = result.data!!.getBundleExtra("extra")
            val binder = extra!!.getBinder("service")
            serviceWork = IShelterService.Stub.asInterface(binder)
            registerStartActivityProxies()
            startKiller()
            window.decorView.post {
                runAntiSpyStartupFreezeIfNeeded()
                AntiSpyManager.syncVpnWatchEverywhere(this@MainActivity)
            }
            buildView()
        }
    }

    private fun runAntiSpyStartupFreezeIfNeeded() {
        val s = storage!!
        if (!s.getBoolean(LocalStorageManager.PREF_ANTI_SPY_BOOT_FREEZE_PENDING, false)) {
            return
        }
        AntiSpyManager.clearStartupFreezePending(s)
        AntiSpyManager.runBatchFreezeAll(this)
        AntiSpyManager.syncVpnWatchEverywhere(this)
    }

    private fun startKiller() {
        val intent = Intent(this, KillerService::class.java)
        val bundle = Bundle().apply {
            putBinder("main", serviceMain!!.asBinder())
            putBinder("work", serviceWork!!.asBinder())
        }
        intent.putExtra("extra", bundle)
        startService(intent)
    }

    private fun buildView() {
        val pager = findViewById<ViewPager2>(R.id.main_pager)
        val nav = findViewById<BottomNavigationView>(R.id.main_bottom_navigation)

        pager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> AppListFragment.newInstance(serviceMain!!, false)
                1 -> AppListFragment.newInstance(serviceWork!!, true)
                else -> throw RuntimeException("How did this happen?")
            }

            override fun getItemCount(): Int = 2
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val menuIds = intArrayOf(
                    R.id.bottom_navigation_main,
                    R.id.bottom_navigation_work
                )
                nav.selectedItemId = menuIds[position]
            }
        })
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.bottom_navigation_main -> pager.currentItem = 0
                R.id.bottom_navigation_work -> pager.currentItem = 1
            }
            true
        }
    }

    fun getOtherService(isRemote: Boolean): IShelterService =
        if (isRemote) serviceMain!! else serviceWork!!

    fun servicesAlive(): Boolean {
        try {
            serviceMain!!.ping()
        } catch (_: Exception) {
            return false
        }
        try {
            serviceWork!!.ping()
        } catch (_: Exception) {
            return false
        }
        return true
    }

    private fun registerStartActivityProxies() {
        try {
            serviceMain!!.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent) {
                    this@MainActivity.startActivity(intent)
                }
            })
            serviceWork!!.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent) {
                    val dummyIntent = Intent(intent.action)
                    Utility.transferIntentToProfileUnsigned(this@MainActivity, dummyIntent)
                    intent.component = dummyIntent.component
                    this@MainActivity.startActivity(intent)
                }
            })
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    override fun onResume() {
        super.onResume()
        AntiSpyManager.syncVpnWatchEverywhere(this)
        if (pendingVpnBlockReason != 0) {
            val reason = pendingVpnBlockReason
            pendingVpnBlockReason = 0
            showAntiSpyVpnLaunchBlockedDialog(reason)
        }
        if (serviceMain != null && serviceWork != null && !servicesAlive()) {
            doOnDestroy()
            restarting = true
            val intent = intent
            finish()
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(antiSpyVpnBlockReceiver)
        super.onDestroy()
        if (!restarting) {
            doOnDestroy()
        }
    }

    private fun doOnDestroy() {
        stopService(Intent(this, KillerService::class.java))
        try {
            serviceWork?.stopShelterService(true)
        } catch (_: Exception) {
        }
        try {
            serviceMain?.stopShelterService(false)
        } catch (_: Exception) {
        }
        AntiSpyManager.syncVpnWatchEverywhere(applicationContext)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity_menu, menu)

        val searchView = menu.findItem(R.id.main_menu_search).actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String): Boolean {
                val intent = Intent(BROADCAST_SEARCH_FILTER_CHANGED).apply {
                    putExtra("text", newText.lowercase().trim())
                }
                LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(intent)
                return true
            }
        })
        return true
    }

    private fun requestAntiSpyNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        postNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestAntiSpyVpnPermission() {
        if (isFinishing) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingVpnBlockReason = AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            retryPendingLaunchAfterVpnPermission()
            return
        }
        antiSpyVpnPermission.launch(prepare)
    }

    private fun retryPendingLaunchAfterVpnPermission() {
        if (TextUtils.isEmpty(pendingLaunchPackageName)) return
        val launchIntent = Intent(DummyActivity.UNFREEZE_AND_LAUNCH).apply {
            component = ComponentName(this@MainActivity, DummyActivity::class.java)
            putExtra("packageName", pendingLaunchPackageName)
        }
        DummyActivity.registerSameProcessRequest(launchIntent)
        startActivity(launchIntent)
    }

    private fun showAntiSpyVpnLaunchBlockedDialog(reason: Int) {
        if (isFinishing) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingVpnBlockReason = reason
            return
        }
        val message = if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
            getString(R.string.anti_spy_vpn_permission_required)
        } else {
            getString(R.string.anti_spy_vpn_block_launch_manual)
        }
        val builder = AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(R.string.anti_spy_vpn_block_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
        if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
            builder.setNeutralButton(R.string.anti_spy_vpn_permission_grant) { _, _ ->
                requestAntiSpyVpnPermission()
            }
        }
        builder.show()
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(BROADCAST_CONTEXT_MENU_CLOSED))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.main_menu_unfreeze_all -> {
                val unfreezeIntent = Intent(DummyActivity.PUBLIC_UNFREEZE_ALL).apply {
                    component = ComponentName(this@MainActivity, DummyActivity::class.java)
                }
                DummyActivity.registerSameProcessRequest(unfreezeIntent)
                startActivity(unfreezeIntent)
                refreshAppLists()
                true
            }
            R.id.main_menu_freeze_all -> {
                val intent = Intent(DummyActivity.PUBLIC_FREEZE_ALL).apply {
                    component = ComponentName(this@MainActivity, DummyActivity::class.java)
                }
                DummyActivity.registerSameProcessRequest(intent)
                startActivity(intent)
                refreshAppLists()
                true
            }
            R.id.main_menu_settings -> {
                val settingsIntent = Intent(this, SettingsActivity::class.java)
                val extras = Bundle().apply {
                    putBinder("profile_service", serviceWork!!.asBinder())
                }
                settingsIntent.putExtra("extras", extras)
                startActivity(settingsIntent)
                true
            }
            R.id.main_menu_create_freeze_all_shortcut -> {
                val launchIntent = Intent(DummyActivity.PUBLIC_FREEZE_ALL).apply {
                    component = ComponentName(this@MainActivity, DummyActivity::class.java)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                Utility.createLauncherShortcut(
                    this, launchIntent,
                    Icon.createWithResource(this, R.drawable.ic_shortcut_freeze),
                    "shelter-freeze-all", getString(R.string.freeze_all_shortcut)
                )
                true
            }
            R.id.main_menu_create_unfreeze_all_shortcut -> {
                val launchIntent = Intent(DummyActivity.PUBLIC_UNFREEZE_ALL).apply {
                    component = ComponentName(this@MainActivity, DummyActivity::class.java)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                Utility.createLauncherShortcut(
                    this, launchIntent,
                    Icon.createWithResource(this, R.drawable.ic_shortcut_unfreeze),
                    "shelter-unfreeze-all", getString(R.string.unfreeze_all_shortcut)
                )
                true
            }
            R.id.main_menu_install_app_to_profile -> {
                selectApk.launch(null)
                true
            }
            R.id.main_menu_show_all -> {
                val update = Runnable {
                    showAll = !item.isChecked
                    item.isChecked = showAll
                    LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(Intent(AppListFragment.BROADCAST_REFRESH))
                }
                if (!item.isChecked) {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.show_all_warning)
                        .setPositiveButton(R.string.first_run_alert_continue) { _, _ -> update.run() }
                        .setNegativeButton(R.string.first_run_alert_cancel, null)
                        .show()
                } else {
                    update.run()
                }
                true
            }
            R.id.main_menu_documents_ui -> {
                val documentsUiIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(null, "vnd.android.document/root")
                }
                startActivity(documentsUiIntent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshAppLists() {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(AppListFragment.BROADCAST_REFRESH))
    }

    private fun onApkSelected(uri: Uri?) {
        if (uri == null) return
        val proxy = UriForwardProxy(applicationContext, uri)
        try {
            serviceWork!!.installApk(proxy, object : IAppInstallCallback.Stub() {
                override fun callback(result: Int) {
                    runOnUiThread {
                        if (result == RESULT_OK) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.install_app_to_profile_success,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            })
        } catch (_: RemoteException) {
        }
    }

    companion object {
        const val BROADCAST_CONTEXT_MENU_CLOSED =
            "net.typeblog.shelter.broadcast.CONTEXT_MENU_CLOSED"
        const val BROADCAST_SEARCH_FILTER_CHANGED =
            "net.typeblog.shelter.broadcast.SEARCH_FILTER_CHANGED"
    }
}
