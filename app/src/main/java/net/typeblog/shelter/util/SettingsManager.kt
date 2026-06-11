package net.typeblog.shelter.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import net.typeblog.shelter.services.PaymentStubService
import net.typeblog.shelter.ui.DummyActivity

class SettingsManager private constructor(context: Context) {
    private val storage: LocalStorageManager = LocalStorageManager.getInstance()
    private val context: Context = context

    private fun syncSettingsToProfileBool(name: String, value: Boolean) {
        val intent = Intent(DummyActivity.SYNCHRONIZE_PREFERENCE)
        intent.putExtra("name", name)
        intent.putExtra("boolean", value)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        Utility.transferIntentToProfile(context, intent)
        context.startActivity(intent)
    }

    private fun syncSettingsToProfileInt(name: String, value: Int) {
        val intent = Intent(DummyActivity.SYNCHRONIZE_PREFERENCE)
        intent.putExtra("name", name)
        intent.putExtra("int", value)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        Utility.transferIntentToProfile(context, intent)
        context.startActivity(intent)
    }

    fun applyAll() {
        applyCrossProfileFileChooser()
        applyPaymentStub()
    }

    fun applyCrossProfileFileChooser() {
        val enabled = storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, CrossProfileDocumentsProvider::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }

    fun setCrossProfileFileChooserEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
        applyCrossProfileFileChooser()
        syncSettingsToProfileBool(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER, enabled)
    }

    fun getCrossProfileFileChooserEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER)

    fun setBlockContactsSearchingEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
        syncSettingsToProfileBool(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING, enabled)
    }

    fun getBlockContactsSearchingEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING)

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE, enabled)
    }

    fun getAutoFreezeServiceEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_AUTO_FREEZE_SERVICE)

    fun setAutoFreezeDelay(seconds: Int) {
        storage.setInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
        syncSettingsToProfileInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY, seconds)
    }

    fun getAutoFreezeDelay(): Int {
        var ret = storage.getInt(LocalStorageManager.PREF_AUTO_FREEZE_DELAY)
        if (ret == Int.MIN_VALUE) {
            ret = 0
        }
        return ret
    }

    fun setSkipForegroundEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
        syncSettingsToProfileBool(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND, enabled)
    }

    fun getSkipForegroundEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND)

    fun getPaymentStubEnabled(): Boolean =
        storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)

    fun setPaymentStubEnabled(enabled: Boolean) {
        storage.setBoolean(LocalStorageManager.PREF_PAYMENT_STUB, enabled)
        applyPaymentStub()
    }

    fun applyPaymentStub() {
        val enabled = storage.getBoolean(LocalStorageManager.PREF_PAYMENT_STUB)
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, PaymentStubService::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }

    companion object {
        private var instance: SettingsManager? = null

        fun initialize(context: Context) {
            instance = SettingsManager(context)
        }

        fun getInstance(): SettingsManager {
            return instance
                ?: throw IllegalStateException("SettingsManager must be initialized at start-up")
        }
    }
}
