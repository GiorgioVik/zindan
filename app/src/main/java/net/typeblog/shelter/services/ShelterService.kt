package net.typeblog.shelter.services

import android.app.Activity
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import net.typeblog.shelter.R
import net.typeblog.shelter.ShelterApplication
import net.typeblog.shelter.receivers.ShelterDeviceAdminReceiver
import net.typeblog.shelter.ui.DummyActivity
import net.typeblog.shelter.util.ApplicationInfoWrapper
import net.typeblog.shelter.util.FileProviderProxy
import net.typeblog.shelter.util.UriForwardProxy
import net.typeblog.shelter.util.Utility
import net.typeblog.shelter.util.WorkProfileBatchFreeze

class ShelterService : Service() {
    private var policyManager: DevicePolicyManager? = null
    private var isProfileOwner = false
    private var packageManager: PackageManager? = null
    private var adminComponent: ComponentName? = null
    private var startActivityProxy: IStartActivityProxy? = null

    private val binder = object : IShelterService.Stub() {
        override fun ping() {
        }

        override fun stopShelterService(kill: Boolean) {
            Thread {
                try {
                    Thread.sleep(1)
                } catch (_: Exception) {
                }

                (application as ShelterApplication).unbindShelterService()

                if (kill && !(isProfileOwner && FreezeService.hasPendingAppToFreeze())) {
                    if (isProfileOwner) {
                        // Keep work process alive for Anti Spy VPN monitoring.
                        return@Thread
                    }
                    System.exit(0)
                }
            }.start()
        }

        override fun getApps(callback: IGetAppsCallback, showAll: Boolean) {
            Thread {
                val pmFlags = PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                val list = packageManager!!.getInstalledApplications(pmFlags)
                    .asSequence()
                    .filter { it.packageName != packageName }
                    .filter {
                        val isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0
                        val isHidden = isHidden(it.packageName)
                        val isInstalled = it.flags and ApplicationInfo.FLAG_INSTALLED != 0
                        val canLaunch = packageManager!!.getLaunchIntentForPackage(it.packageName) != null
                        showAll || (!isSystem && isInstalled) || isHidden || canLaunch
                    }
                    .map { ApplicationInfoWrapper(it) }
                    .map { wrapper -> wrapper.loadLabel(packageManager!!).setHidden(isHidden(wrapper.getPackageName())) }
                    .sortedWith { x, y ->
                        when {
                            x.isHidden() && !y.isHidden() -> -1
                            !x.isHidden() && y.isHidden() -> 1
                            else -> x.getLabel()!!.compareTo(y.getLabel()!!, ignoreCase = true)
                        }
                    }
                    .toList()

                try {
                    callback.callback(list)
                } catch (_: RemoteException) {
                }
            }.start()
        }

        override fun loadIcon(info: ApplicationInfoWrapper, callback: ILoadIconCallback) {
            Thread {
                val icon = Utility.drawableToBitmap(
                    info.getInfo()!!.loadUnbadgedIcon(packageManager!!),
                    LIST_ICON_MAX_PX,
                )
                try {
                    callback.callback(icon)
                } catch (_: RemoteException) {
                }
            }.start()
        }

        override fun installApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
            if (!app.isSystem()) {
                val intent = Intent(DummyActivity.INSTALL_PACKAGE)
                intent.component = ComponentName(this@ShelterService, DummyActivity::class.java)
                intent.putExtra("package", app.getPackageName())
                intent.putExtra("apk", app.getSourceDir())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.putExtra("split_apks", app.getSplitApks())
                }

                val callbackExtra = Bundle()
                callbackExtra.putBinder("callback", callback.asBinder())
                intent.putExtra("callback", callbackExtra)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                DummyActivity.registerSameProcessRequest(intent)
                startActivityProxy?.startActivity(intent)
            } else {
                if (isProfileOwner) {
                    policyManager!!.enableSystemApp(adminComponent!!, app.getPackageName())
                    policyManager!!.setApplicationHidden(adminComponent!!, app.getPackageName(), false)
                    callback.callback(Activity.RESULT_OK)
                } else {
                    callback.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP)
                }
            }
        }

        override fun installApk(uriForwarder: UriForwardProxy, callback: IAppInstallCallback) {
            val intent = Intent(DummyActivity.INSTALL_PACKAGE)
            intent.component = ComponentName(this@ShelterService, DummyActivity::class.java)
            val uri: Uri = FileProviderProxy.setUriForwardProxy(uriForwarder, "apk")
            intent.putExtra("direct_install_apk", uri)

            val callbackExtra = Bundle()
            callbackExtra.putBinder("callback", callback.asBinder())
            intent.putExtra("callback", callbackExtra)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DummyActivity.registerSameProcessRequest(intent)
            startActivityProxy?.startActivity(intent)
        }

        override fun uninstallApp(app: ApplicationInfoWrapper, callback: IAppInstallCallback) {
            if (!app.isSystem()) {
                val intent = Intent(DummyActivity.UNINSTALL_PACKAGE)
                intent.component = ComponentName(this@ShelterService, DummyActivity::class.java)
                intent.putExtra("package", app.getPackageName())

                val callbackExtra = Bundle()
                callbackExtra.putBinder("callback", callback.asBinder())
                intent.putExtra("callback", callbackExtra)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                DummyActivity.registerSameProcessRequest(intent)
                startActivityProxy?.startActivity(intent)
            } else {
                if (isProfileOwner) {
                    policyManager!!.setApplicationHidden(adminComponent!!, app.getPackageName(), true)
                    callback.callback(Activity.RESULT_OK)
                } else {
                    callback.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP)
                }
            }
        }

        override fun freezeApp(app: ApplicationInfoWrapper) {
            check(isProfileOwner) { "Cannot freeze app without being profile owner" }
            WorkProfileBatchFreeze.freezeOne(this@ShelterService, app.getPackageName())
        }

        override fun unfreezeApp(app: ApplicationInfoWrapper) {
            check(isProfileOwner) { "Cannot unfreeze app without being profile owner" }
            policyManager!!.setApplicationHidden(adminComponent!!, app.getPackageName(), false)
        }

        override fun hasUsageStatsPermission(): Boolean =
            Utility.checkUsageStatsPermission(this@ShelterService)

        override fun hasSystemAlertPermission(): Boolean =
            Utility.checkSystemAlertPermission(this@ShelterService)

        override fun hasAllFileAccessPermission(): Boolean =
            Utility.checkAllFileAccessPermission()

        override fun getCrossProfileWidgetProviders(): List<String> {
            check(isProfileOwner) {
                "Cannot access cross-profile widget providers without being profile owner"
            }
            return policyManager!!.getCrossProfileWidgetProviders(adminComponent!!)
        }

        override fun setCrossProfileWidgetProviderEnabled(pkgName: String, enabled: Boolean): Boolean {
            check(isProfileOwner) {
                "Cannot access cross-profile widget providers without being profile owner"
            }
            return if (enabled) {
                policyManager!!.addCrossProfileWidgetProvider(adminComponent!!, pkgName)
            } else {
                policyManager!!.removeCrossProfileWidgetProvider(adminComponent!!, pkgName)
            }
        }

        override fun setStartActivityProxy(proxy: IStartActivityProxy) {
            startActivityProxy = proxy
        }

        override fun getCrossProfilePackages(): List<String> {
            check(isProfileOwner) {
                "Cannot access cross-profile packages without being profile owner"
            }
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Cross-profile packages support is only available on Android 11 and later"
            }
            return ArrayList(policyManager!!.getCrossProfilePackages(adminComponent!!))
        }

        override fun setCrossProfilePackages(packages: List<String>) {
            check(isProfileOwner) {
                "Cannot access cross-profile packages without being profile owner"
            }
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Cross-profile packages support is only available on Android 11 and later"
            }
            policyManager!!.setCrossProfilePackages(adminComponent!!, HashSet(packages))
        }
    }

    override fun onCreate() {
        policyManager = getSystemService(DevicePolicyManager::class.java)
        packageManager = getPackageManager()
        isProfileOwner = policyManager!!.isProfileOwnerApp(packageName)
        adminComponent = ComponentName(applicationContext, ShelterDeviceAdminReceiver::class.java)
    }

    override fun onBind(intent: Intent?): IBinder {
        if (intent?.getBooleanExtra("foreground", false) == true) {
            setForeground()
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopForeground(true)
        return false
    }

    private fun isHidden(packageName: String): Boolean {
        return isProfileOwner &&
            policyManager!!.isApplicationHidden(adminComponent!!, packageName)
    }

    private fun setForeground() {
        startForeground(
            NOTIFICATION_ID,
            Utility.buildNotification(
                this,
                getString(R.string.app_name),
                getString(R.string.service_title),
                getString(R.string.service_desc),
                R.drawable.ic_notification_white_24dp,
            ),
        )
        if (isProfileOwner) {
            AntiSpyVpnWatchService.syncState(this)
        }
    }

    companion object {
        const val RESULT_CANNOT_INSTALL_SYSTEM_APP = 100001
        private const val NOTIFICATION_ID = 0x49a11
        private const val LIST_ICON_MAX_PX = 128
    }
}
