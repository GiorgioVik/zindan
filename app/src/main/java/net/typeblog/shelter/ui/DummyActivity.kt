package net.typeblog.shelter.ui

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.StrictMode
import net.typeblog.shelter.util.ZindanToast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import net.typeblog.shelter.R
import net.typeblog.shelter.ShelterApplication
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver
import net.typeblog.shelter.services.AntiSpyVpnWatchService
import net.typeblog.shelter.services.FreezeService
import net.typeblog.shelter.services.IAppInstallCallback
import net.typeblog.shelter.services.IFileShuttleService
import net.typeblog.shelter.services.IFileShuttleServiceCallback
import net.typeblog.shelter.util.AntiSpyLaunchGate
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.AntiSpyVpnGuard
import net.typeblog.shelter.util.AuthenticationUtility
import net.typeblog.shelter.util.AutoFreezeDefaults
import net.typeblog.shelter.util.FileProviderProxy
import net.typeblog.shelter.util.InstallationProgressListener
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.SettingsManager
import net.typeblog.shelter.util.Utility
import net.typeblog.shelter.util.WorkProfileBatchFreeze
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.UUID

class DummyActivity : Activity() {
    private var isProfileOwner = false
    private var policyManager: DevicePolicyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        policyManager = getSystemService(DevicePolicyManager::class.java)
        isProfileOwner = policyManager!!.isProfileOwnerApp(packageName)
        if (isProfileOwner) {
            Utility.enforceWorkProfilePolicies(this)
            Utility.enforceUserRestrictions(this)
            SettingsManager.getInstance().applyAll()

            synchronized(DummyActivity::class.java) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasRequestedPermission
                    && FINALIZE_PROVISION != intent.action
                ) {
                    hasRequestedPermission = true
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_PERMISSION_POST_NOTIFICATIONS
                        )
                        return
                    }
                }
            }
        }

        init()
    }

    private fun init() {
        val intent = intent

        if (!checkSameProcessRequest(intent)) {
            if (!AuthenticationUtility.checkIntent(intent)) {
                if (intent.action !in ACTIONS_ALLOWED_WITHOUT_SIGNATURE) {
                    finish()
                    return
                }
            }
        }

        when (intent.action) {
            START_SERVICE -> actionStartService()
            TRY_START_SERVICE -> {
                setResult(RESULT_OK)
                finish()
            }
            INSTALL_PACKAGE -> actionInstallPackage()
            UNINSTALL_PACKAGE -> actionUninstallPackage()
            FINALIZE_PROVISION -> actionFinalizeProvision()
            UNFREEZE_AND_LAUNCH, PUBLIC_UNFREEZE_AND_LAUNCH -> actionUnfreezeAndLaunch()
            UNFREEZE_APP -> actionUnfreezeApp()
            PUBLIC_FREEZE_ALL -> actionPublicFreezeAll()
            PUBLIC_UNFREEZE_ALL -> actionPublicUnfreezeAll()
            SHOW_TOAST -> actionShowToast()
            FREEZE_ALL_IN_LIST -> actionFreezeAllInList()
            UNFREEZE_ALL_IN_LIST -> actionUnfreezeAllInList()
            ENABLE_AUTO_FREEZE_WORK_PROFILE -> actionEnableAutoFreezeWorkProfile()
            REMOVE_UNFREEZE_SHORTCUT -> actionRemoveUnfreezeShortcut()
            START_FILE_SHUTTLE, START_FILE_SHUTTLE_2 -> actionStartFileShuttle()
            SYNCHRONIZE_PREFERENCE -> actionSynchronizePreference()
            SYNC_ANTI_SPY_VPN_WATCH -> actionSyncAntiSpyVpnWatch()
            PACKAGEINSTALLER_CALLBACK -> handlePackageInstallerCallback(intent)
            else -> finish()
        }
    }

    private fun handlePackageInstallerCallback(callbackIntent: Intent) {
        val status = callbackIntent.extras!!.getInt(PackageInstaller.EXTRA_STATUS)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                startActivity(callbackIntent.extras!!.get(Intent.EXTRA_INTENT) as Intent)
            }
            PackageInstaller.STATUS_SUCCESS -> appInstallFinished(RESULT_OK)
            else -> appInstallFinished(RESULT_CANCELED)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.action == PACKAGEINSTALLER_CALLBACK) {
            handlePackageInstallerCallback(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ANTI_SPY_VPN) {
            if (resultCode == RESULT_OK) {
                runAntiSpyLaunchGate()
            } else {
                showAntiSpyVpnPermissionDeniedDialog()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_INSTALL_PACKAGE) {
            appInstallFinished(resultCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_PERMISSION_EXTERNAL_STORAGE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    doStartFileShuttle()
                } else {
                    finish()
                }
            }
            REQUEST_PERMISSION_POST_NOTIFICATIONS -> init()
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun actionFinalizeProvision() {
        if (isProfileOwner) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val intent = Intent(FINALIZE_PROVISION)
                Utility.transferIntentToProfileUnsigned(this, intent)
                startActivity(intent)
            }
            finish()
        } else {
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
            LocalStorageManager.getInstance()
                .setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            val intent = Intent(SetupWizardActivity.ACTION_PROFILE_PROVISIONED).apply {
                component = ComponentName(this@DummyActivity, SetupWizardActivity::class.java)
            }
            startActivity(intent)
            ZindanToast.show(this, getString(R.string.provision_finished), android.widget.Toast.LENGTH_LONG)
            finish()
        }
    }

    private fun actionStartService() {
        (application as ShelterApplication).bindShelterService(object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val data = Intent()
                val bundle = Bundle().apply {
                    putBinder("service", service)
                }
                data.putExtra("extra", bundle)
                setResult(RESULT_OK, data)
                finish()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // dummy
            }
        }, true)
    }

    private fun actionInstallPackage() {
        capturePendingPackageOperation(OperationType.INSTALL)
        var uri: Uri? = null
        if (intent.hasExtra("package")) {
            uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        }
        val policy = StrictMode.getVmPolicy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || intent.hasExtra("direct_install_apk")) {
            if (intent.hasExtra("apk")) {
                uri = Uri.fromFile(File(intent.getStringExtra("apk")!!))
            } else if (intent.hasExtra("direct_install_apk")) {
                uri = intent.getParcelableExtra("direct_install_apk")
            }
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                actionInstallPackageQ(uri, intent.getStringArrayExtra("split_apks"))
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        } else {
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, packageName)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(installIntent, REQUEST_INSTALL_PACKAGE)
        }

        StrictMode.setVmPolicy(policy)
    }

    @Throws(IOException::class)
    private fun actionInstallPackageQ(uri: Uri?, splitApks: Array<String>?) {
        val pi = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = pi.createSession(params)

        pi.registerSessionCallback(InstallationProgressListener(this, pi, sessionId))

        val session = pi.openSession(sessionId)
        doInstallPackageQ(uri, splitApks, session) {
            session.setStagingProgress(0.1f)
            val callbackIntent = Intent(this, DummyActivity::class.java).apply {
                action = PACKAGEINSTALLER_CALLBACK
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, callbackIntent, PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun doInstallPackageQ(
        baseUri: Uri?,
        splitApks: Array<String>?,
        session: PackageInstaller.Session,
        callback: Runnable
    ) {
        val uris = ArrayList<Uri>()
        uris.add(baseUri!!)
        if (splitApks != null && splitApks.isNotEmpty()) {
            for (apk in splitApks) {
                uris.add(Uri.fromFile(File(apk)))
            }
        }

        Thread {
            for (uri in uris) {
                try {
                    contentResolver.openInputStream(uri).use { input ->
                        session.openWrite(UUID.randomUUID().toString(), 0, input!!.available().toLong())
                            .use { output ->
                                Utility.pipe(input, output)
                                session.fsync(output)
                            }
                    }
                } catch (_: IOException) {
                }
            }
            runOnUiThread(callback)
        }.start()
    }

    private fun actionUninstallPackage() {
        capturePendingPackageOperation(OperationType.UNINSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            actionUninstallPackageQ()
            return
        }

        val uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(uninstallIntent, REQUEST_INSTALL_PACKAGE)
    }

    private fun actionUninstallPackageQ() {
        val pi = packageManager.packageInstaller
        val callbackIntent = Intent(this, DummyActivity::class.java).apply {
            action = PACKAGEINSTALLER_CALLBACK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, callbackIntent, PendingIntent.FLAG_MUTABLE
        )
        pi.uninstall(requireNotNull(intent.getStringExtra("package")), pendingIntent.intentSender)
    }

    private fun appInstallFinished(resultCode: Int) {
        FileProviderProxy.clearForwardProxy()

        val pending = consumePendingPackageOperation()
        var callback: IAppInstallCallback? = null
        if (intent.hasExtra("callback")) {
            val callbackExtra = intent.getBundleExtra("callback")
            callback = IAppInstallCallback.Stub.asInterface(callbackExtra!!.getBinder("callback"))
        }
        if (callback == null) {
            callback = pending?.callback
        }

        val packageName = pending?.packageName ?: intent.getStringExtra("package")
        val operationType = pending?.type ?: operationTypeFromIntentAction(intent.action)

        if (resultCode == RESULT_OK && packageName != null && operationType != null) {
            onPackageOperationFinished(operationType, packageName)
        }

        try {
            callback?.callback(resultCode)
        } catch (_: RemoteException) {
        }

        finish()
    }

    private fun onPackageOperationFinished(type: OperationType, packageName: String) {
        when (type) {
            OperationType.INSTALL -> {
                if (isProfileOwner) {
                    AutoFreezeDefaults.requestEnableOnMainProfile(this, packageName)
                }
            }
            OperationType.UNINSTALL -> {
                if (isProfileOwner) {
                    Utility.requestRemoveUnfreezeShortcutOnOtherProfile(this, packageName)
                } else {
                    Utility.removeUnfreezeLauncherShortcutsEverywhere(this, packageName)
                    LocalStorageManager.getInstance().removeFromStringList(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        packageName
                    )
                }
            }
        }
    }

    private fun capturePendingPackageOperation(type: OperationType) {
        val packageName = intent.getStringExtra("package") ?: return
        if (!intent.hasExtra("callback")) {
            return
        }
        val callbackExtra = intent.getBundleExtra("callback")!!
        val callback = IAppInstallCallback.Stub.asInterface(callbackExtra.getBinder("callback"))
        registerPendingPackageOperation(PendingPackageOperation(type, packageName, callback))
    }

    private fun operationTypeFromIntentAction(action: String?): OperationType? = when (action) {
        INSTALL_PACKAGE -> OperationType.INSTALL
        UNINSTALL_PACKAGE -> OperationType.UNINSTALL
        else -> null
    }

    private fun actionUnfreezeAndLaunch() {
        if (isProfileOwner && PUBLIC_UNFREEZE_AND_LAUNCH == intent.action) {
            if (AntiSpyVpnGuard.forwardPublicUnfreezeToParent(this, intent)) {
                finish()
                return
            }
        }

        if (!isProfileOwner) {
            if (intent.getStringExtra("packageName") == null) {
                finish()
                return
            }
            if (!ensureAntiSpyVpnPermissionThenLaunch()) {
                return
            }
            val packageName = requireNotNull(intent.getStringExtra("packageName"))
            val proceed = Runnable {
                forwardUnfreezeAndLaunchToWorkProfile()
                finish()
            }
            if (AntiSpyLaunchGate.shouldApplyVpnGate(packageName)) {
                runAntiSpyLaunchGate()
            } else {
                proceed.run()
            }
            return
        }

        if (intent.hasExtra("linkedPackages")) {
            val packages = intent.getStringArrayExtra("linkedPackages")!!
            val packagesShouldFreeze = intent.getBooleanArrayExtra("linkedPackagesShouldFreeze")!!

            for (i in packages.indices) {
                policyManager!!.setApplicationHidden(
                    ComponentName(this, ShelterDeviceAdminReceiver::class.java),
                    packages[i], false
                )
                if (packagesShouldFreeze[i]) {
                    registerAppToFreeze(packages[i])
                }
            }
        }

        val packageName = intent.getStringExtra("packageName")!!

        policyManager!!.setApplicationHidden(
            ComponentName(this, ShelterDeviceAdminReceiver::class.java),
            packageName, false
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            if (intent.getBooleanExtra("shouldFreeze", false)) {
                registerAppToFreeze(packageName)
            }
            startActivity(launchIntent)
        } else {
            ZindanToast.show(this, getString(R.string.launch_app_fail, packageName))
        }

        finish()
    }

    private fun ensureAntiSpyVpnPermissionThenLaunch(): Boolean {
        val storage = LocalStorageManager.getInstance()
        if (!AntiSpyLaunchGate.needsVpnClear(this, storage)) {
            return true
        }
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            return true
        }
        @Suppress("DEPRECATION")
        startActivityForResult(prepare, REQUEST_ANTI_SPY_VPN)
        return false
    }

    private fun runAntiSpyLaunchGate() {
        AntiSpyLaunchGate.runBeforeLaunch(
            this, LocalStorageManager.getInstance(),
            requireNotNull(intent.getStringExtra("packageName")),
            {
                forwardUnfreezeAndLaunchToWorkProfile()
                finish()
            },
            { reason ->
                if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                    if (ensureAntiSpyVpnPermissionThenLaunch()) {
                        runAntiSpyLaunchGate()
                    }
                    return@runBeforeLaunch
                }
                finish()
            }
        )
    }

    private fun showAntiSpyVpnPermissionDeniedDialog() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(R.string.anti_spy_vpn_block_title)
            .setMessage(R.string.anti_spy_vpn_permission_required)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNeutralButton(R.string.anti_spy_vpn_permission_grant) { _, _ ->
                if (ensureAntiSpyVpnPermissionThenLaunch()) {
                    runAntiSpyLaunchGate()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun forwardUnfreezeAndLaunchToWorkProfile() {
        val forwardIntent = Intent(UNFREEZE_AND_LAUNCH)
        Utility.transferIntentToProfile(this, forwardIntent)
        val packageName = requireNotNull(intent.getStringExtra("packageName"))
        forwardIntent.putExtra("packageName", packageName)
        forwardIntent.putExtra(
            "shouldFreeze",
            SettingsManager.getInstance().getAutoFreezeServiceEnabled() &&
                LocalStorageManager.getInstance()
                    .stringListContains(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        packageName
                    )
        )
        if (intent.hasExtra("linkedPackages")) {
            val packages = intent.getStringExtra("linkedPackages")!!.split(",").toTypedArray()
            val packagesShouldFreeze = BooleanArray(packages.size)
            for (i in packages.indices) {
                packagesShouldFreeze[i] = SettingsManager.getInstance().getAutoFreezeServiceEnabled() &&
                    LocalStorageManager.getInstance()
                        .stringListContains(
                            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                            packages[i]
                        )
            }
            forwardIntent.putExtra("linkedPackages", packages)
            forwardIntent.putExtra("linkedPackagesShouldFreeze", packagesShouldFreeze)
        }
        startActivity(forwardIntent)
    }

    private fun actionEnableAutoFreezeWorkProfile() {
        if (!isProfileOwner) {
            val packageName = intent.getStringExtra("packageName")
            if (packageName != null) {
                AutoFreezeDefaults.enableForWorkProfile(this, packageName)
            }
        }
        finish()
    }

    private fun registerAppToFreeze(packageName: String) {
        FreezeService.registerAppToFreeze(packageName)
        startService(Intent(this, FreezeService::class.java))
    }

    private fun finishBatchShortcutFlow() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun forwardBatchToMainActivityIfVisible(batchAction: String): Boolean {
        if (!MainActivity.isResumed) return false
        val mainAction = when (batchAction) {
            PUBLIC_FREEZE_ALL -> MainActivity.ACTION_BATCH_FREEZE_ALL
            PUBLIC_UNFREEZE_ALL -> MainActivity.ACTION_BATCH_UNFREEZE_ALL
            else -> return false
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = mainAction
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finishBatchShortcutFlow()
        return true
    }

    private fun actionShowToast() {
        val resId = intent.getIntExtra(MainActivity.EXTRA_TOAST_RES_ID, 0)
        if (resId != 0) {
            ZindanToast.show(this, resId)
        }
        finishBatchShortcutFlow()
    }

    private fun actionPublicFreezeAll() {
        if (!isProfileOwner) {
            if (forwardBatchToMainActivityIfVisible(PUBLIC_FREEZE_ALL)) return
            AntiSpyManager.syncAutoFreezeListToWorkProfile(this)
            Utility.launchFreezeInWorkProfile(this, AntiSpyManager.getAutoFreezeList(this))
            finishBatchShortcutFlow()
        } else {
            throw RuntimeException("unimplemented")
        }
    }

    private fun actionUnfreezeApp() {
        if (!isProfileOwner) {
            val packageName = intent.getStringExtra("packageName") ?: run {
                finish()
                return
            }
            if (!ensureAntiSpyVpnPermissionThenLaunch()) {
                return
            }
            val proceed = Runnable {
                forwardUnfreezeAppToWorkProfile(packageName)
                finish()
            }
            if (AntiSpyLaunchGate.shouldApplyVpnGate(packageName)) {
                AntiSpyLaunchGate.runBeforeLaunch(
                    this, LocalStorageManager.getInstance(), packageName,
                    proceed,
                    { reason ->
                        if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                            if (ensureAntiSpyVpnPermissionThenLaunch()) {
                                actionUnfreezeApp()
                            }
                            return@runBeforeLaunch
                        }
                        finish()
                    }
                )
            } else {
                proceed.run()
            }
            return
        }

        val packageName = intent.getStringExtra("packageName") ?: run {
            finish()
            return
        }
        policyManager!!.setApplicationHidden(
            ComponentName(this, ShelterDeviceAdminReceiver::class.java),
            packageName, false
        )
        Utility.scheduleAppListRefresh(this)
        finish()
    }

    private fun forwardUnfreezeAppToWorkProfile(packageName: String) {
        val forwardIntent = Intent(UNFREEZE_APP)
        Utility.transferIntentToProfile(this, forwardIntent)
        forwardIntent.putExtra("packageName", packageName)
        startActivity(forwardIntent)
    }

    private fun actionPublicUnfreezeAll() {
        if (!isProfileOwner) {
            if (forwardBatchToMainActivityIfVisible(PUBLIC_UNFREEZE_ALL)) return
            if (!ensureAntiSpyVpnPermissionThenLaunch()) {
                return
            }
            AntiSpyLaunchGate.runBeforeLaunch(
                this, LocalStorageManager.getInstance(), "",
                {
                    val forwardIntent = Intent(UNFREEZE_ALL_IN_LIST)
                    Utility.transferIntentToProfile(this, forwardIntent)
                    val list = LocalStorageManager.getInstance()
                        .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
                    forwardIntent.putExtra("list", list)
                    startActivity(forwardIntent)
                    finishBatchShortcutFlow()
                },
                { reason ->
                    if (reason == AntiSpyLaunchGate.REASON_VPN_PERMISSION_REQUIRED) {
                        if (ensureAntiSpyVpnPermissionThenLaunch()) {
                            actionPublicUnfreezeAll()
                        }
                        return@runBeforeLaunch
                    }
                    finish()
                }
            )
        } else {
            throw RuntimeException("unimplemented")
        }
    }

    private fun actionFreezeAllInList() {
        if (isProfileOwner) {
            val list = intent.getStringArrayExtra("list") ?: run {
                finish()
                return
            }
            val frozen = WorkProfileBatchFreeze.freezeList(this, list)
            if (frozen > 0) {
                Utility.postVpnAutoFreezeSuccessAlert(this)
                Utility.showToastOnMainProfile(this, R.string.freeze_all_success)
            }
            Utility.scheduleAppListRefresh(this)
            finish()
        } else {
            finish()
        }
    }

    private fun actionUnfreezeAllInList() {
        if (isProfileOwner) {
            val list = intent.getStringArrayExtra("list")
            if (list != null) {
                val admin = ComponentName(this, ShelterDeviceAdminReceiver::class.java)
                for (pkg in list) {
                    if (pkg.isNullOrEmpty()) continue
                    policyManager!!.setApplicationHidden(admin, pkg, false)
                }
            }
            stopService(Intent(this, FreezeService::class.java))
            Utility.showToastOnMainProfile(this, R.string.unfreeze_all_success)
            finish()
        } else {
            finish()
        }
    }

    private fun actionStartFileShuttle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                doStartFileShuttle()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_PERMISSION_EXTERNAL_STORAGE
                )
            }
        } else {
            if (Utility.checkAllFileAccessPermission() && Utility.checkSystemAlertPermission(this)) {
                doStartFileShuttle()
            } else {
                finish()
            }
        }
    }

    private fun doStartFileShuttle() {
        (application as ShelterApplication).bindFileShuttleService(object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val shuttle = IFileShuttleService.Stub.asInterface(service)
                val callback = IFileShuttleServiceCallback.Stub.asInterface(
                    intent.getBundleExtra("extra")!!.getBinder("callback")
                )
                try {
                    callback.callback(shuttle)
                } catch (_: RemoteException) {
                }
                finish()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // Do Nothing
            }
        })
    }

    private fun actionSynchronizePreference() {
        val name = requireNotNull(intent.getStringExtra("name"))
        if (intent.hasExtra("boolean")) {
            LocalStorageManager.getInstance()
                .setBoolean(name, intent.getBooleanExtra("boolean", false))
        } else if (intent.hasExtra("int")) {
            LocalStorageManager.getInstance()
                .setInt(name, intent.getIntExtra("int", Int.MIN_VALUE))
        }
        SettingsManager.getInstance().applyAll()
        if (isProfileOwner) {
            Utility.enforceWorkProfilePolicies(this)
        }
        finish()
    }

    private fun actionRemoveUnfreezeShortcut() {
        val packageName = intent.getStringExtra("packageName") ?: run {
            finish()
            return
        }
        Utility.removeUnfreezeLauncherShortcuts(this, packageName)
        if (!isProfileOwner) {
            LocalStorageManager.getInstance().removeFromStringList(
                LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                packageName
            )
        }
        finish()
    }

    private fun actionSyncAntiSpyVpnWatch() {
        if (isProfileOwner) {
            val storage = LocalStorageManager.getInstance()
            if (intent.hasExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST)) {
                val list = intent.getStringArrayExtra(AntiSpyManager.EXTRA_AUTO_FREEZE_LIST)
                if (list != null) {
                    storage.setStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE, list)
                }
            }
            AntiSpyVpnWatchService.syncState(this)
        }
        finish()
    }

    companion object {
        const val FINALIZE_PROVISION = "net.typeblog.shelter.action.FINALIZE_PROVISION"
        const val START_SERVICE = "net.typeblog.shelter.action.START_SERVICE"
        const val TRY_START_SERVICE = "net.typeblog.shelter.action.TRY_START_SERVICE"
        const val INSTALL_PACKAGE = "net.typeblog.shelter.action.INSTALL_PACKAGE"
        const val UNINSTALL_PACKAGE = "net.typeblog.shelter.action.UNINSTALL_PACKAGE"
        const val UNFREEZE_AND_LAUNCH = "net.typeblog.shelter.action.UNFREEZE_AND_LAUNCH"
        const val PUBLIC_UNFREEZE_AND_LAUNCH = "net.typeblog.shelter.action.PUBLIC_UNFREEZE_AND_LAUNCH"
        const val UNFREEZE_APP = "net.typeblog.shelter.action.UNFREEZE_APP"
        const val PUBLIC_FREEZE_ALL = "net.typeblog.shelter.action.PUBLIC_FREEZE_ALL"
        const val PUBLIC_UNFREEZE_ALL = "net.typeblog.shelter.action.PUBLIC_UNFREEZE_ALL"
        const val SHOW_TOAST = "net.typeblog.shelter.action.SHOW_TOAST"
        const val FREEZE_ALL_IN_LIST = "net.typeblog.shelter.action.FREEZE_ALL_IN_LIST"
        const val UNFREEZE_ALL_IN_LIST = "net.typeblog.shelter.action.UNFREEZE_ALL_IN_LIST"
        const val ENABLE_AUTO_FREEZE_WORK_PROFILE =
            "net.typeblog.shelter.action.ENABLE_AUTO_FREEZE_WORK_PROFILE"
        const val REMOVE_UNFREEZE_SHORTCUT =
            "net.typeblog.shelter.action.REMOVE_UNFREEZE_SHORTCUT"
        const val START_FILE_SHUTTLE = "net.typeblog.shelter.action.START_FILE_SHUTTLE"
        const val START_FILE_SHUTTLE_2 = "net.typeblog.shelter.action.START_FILE_SHUTTLE_2"
        const val SYNCHRONIZE_PREFERENCE = "net.typeblog.shelter.action.SYNCHRONIZE_PREFERENCE"
        const val SYNC_ANTI_SPY_VPN_WATCH =
            "net.typeblog.shelter.action.SYNC_ANTI_SPY_VPN_WATCH"
        const val PACKAGEINSTALLER_CALLBACK = "net.typeblog.shelter.action.PACKAGEINSTALLER_CALLBACK"

        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE = listOf(
            FINALIZE_PROVISION,
            PUBLIC_FREEZE_ALL,
            PUBLIC_UNFREEZE_ALL,
            PUBLIC_UNFREEZE_AND_LAUNCH
        )

        private val ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS = listOf(
            INSTALL_PACKAGE,
            UNINSTALL_PACKAGE,
            UNFREEZE_AND_LAUNCH,
            UNFREEZE_APP
        )

        private const val REQUEST_INSTALL_PACKAGE = 1
        private const val REQUEST_PERMISSION_EXTERNAL_STORAGE = 2
        private const val REQUEST_PERMISSION_POST_NOTIFICATIONS = 3
        private const val REQUEST_ANTI_SPY_VPN = 4

        private var hasRequestedPermission = false
        @Volatile
        private var lastSameProcessRequest: Long = -1

        @Volatile
        private var pendingPackageOperation: PendingPackageOperation? = null

        @Synchronized
        private fun registerPendingPackageOperation(operation: PendingPackageOperation) {
            pendingPackageOperation = operation
        }

        @Synchronized
        private fun consumePendingPackageOperation(): PendingPackageOperation? {
            val operation = pendingPackageOperation
            pendingPackageOperation = null
            return operation
        }

        private enum class OperationType {
            INSTALL, UNINSTALL
        }

        private data class PendingPackageOperation(
            val type: OperationType,
            val packageName: String,
            val callback: IAppInstallCallback
        )

        @Synchronized
        fun registerSameProcessRequest(intent: Intent) {
            lastSameProcessRequest = Date().time
            intent.putExtra("is_same_process", true)
        }

        @Synchronized
        private fun checkSameProcessRequest(intent: Intent): Boolean {
            if (!intent.getBooleanExtra("is_same_process", false)) return false
            if (lastSameProcessRequest == -1L) return false

            val ret = Date().time - lastSameProcessRequest <= 5000 &&
                intent.action in ACTIONS_ALLOWED_WITHOUT_SIGNATURE_SAME_PROCESS
            if (ret) {
                lastSameProcessRequest = -1
            }
            return ret
        }
    }
}
