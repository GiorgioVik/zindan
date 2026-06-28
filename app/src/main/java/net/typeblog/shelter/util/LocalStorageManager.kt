package net.typeblog.shelter.util

import android.content.Context
import android.content.SharedPreferences

class LocalStorageManager private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private var prefs: SharedPreferences = prefs()

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun remove(pref: String) {
        prefs.edit().remove(pref).apply()
    }

    fun contains(pref: String): Boolean = prefs.contains(pref)

    fun getBoolean(pref: String): Boolean = prefs.getBoolean(pref, false)

    fun getBoolean(pref: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(pref, defaultValue)

    fun setBoolean(pref: String, value: Boolean) {
        prefs.edit().putBoolean(pref, value).apply()
    }

    fun getInt(pref: String): Int = prefs.getInt(pref, Int.MIN_VALUE)

    fun setInt(pref: String, value: Int) {
        prefs.edit().putInt(pref, value).apply()
    }

    fun getLong(pref: String, defaultValue: Long = 0L): Long =
        prefs.getLong(pref, defaultValue)

    fun setLong(pref: String, value: Long) {
        prefs.edit().putLong(pref, value).apply()
    }

    fun getString(pref: String): String? = prefs.getString(pref, null)

    fun setString(pref: String, value: String) {
        prefs.edit().putString(pref, value).apply()
    }

    fun getStringList(pref: String): Array<String> =
        prefs.getString(pref, "")!!
            .split(LIST_DIVIDER)
            .filter { it.isNotEmpty() }
            .toTypedArray()

    /** Re-read from disk (needed for the {@code :vpnwatch} process). */
    fun getStringListFresh(pref: String): Array<String> =
        prefs().getString(pref, "")!!
            .split(LIST_DIVIDER)
            .filter { it.isNotEmpty() }
            .toTypedArray()

    fun getBooleanFresh(pref: String, defaultValue: Boolean): Boolean =
        prefs().getBoolean(pref, defaultValue)

    fun setStringList(pref: String, list: Array<String>) {
        prefs.edit().putString(pref, Utility.stringJoin(LIST_DIVIDER, list)).apply()
    }

    fun stringListContains(pref: String, item: String): Boolean =
        getStringList(pref).indexOf(item) >= 0

    fun appendStringList(pref: String, newItem: String) {
        var str = prefs.getString(pref, null)
        str = if (str == null) {
            newItem
        } else {
            str + LIST_DIVIDER + newItem
        }
        prefs.edit().putString(pref, str).apply()
    }

    fun removeFromStringList(pref: String, item: String) {
        val list = ArrayList(getStringList(pref).toList())
        list.removeIf { it == item }
        setStringList(pref, list.toTypedArray())
    }

    companion object {
        const val PREF_IS_SETTING_UP = "is_setting_up"
        const val PREF_HAS_SETUP = "has_setup"
        const val PREF_AUTO_FREEZE_LIST_WORK_PROFILE = "auto_freeze_list_work_profile"
        const val PREF_CROSS_PROFILE_FILE_CHOOSER = "cross_profile_file_chooser"
        const val PREF_AUTH_KEY = "auth_key"
        const val PREF_AUTO_FREEZE_SERVICE = "auto_freeze_service"
        const val PREF_DONT_FREEZE_FOREGROUND = "dont_freeze_foreground"
        const val PREF_AUTO_FREEZE_DELAY = "auto_freeze_delay"
        const val PREF_BLOCK_CONTACTS_SEARCHING = "block_contacts_searching"
        const val PREF_PAYMENT_STUB = "payment_stub"
        const val PREF_ANTI_SPY_BOOT_FREEZE_PENDING = "anti_spy_boot_freeze_pending"
        const val PREF_ANTI_SPY_LAUNCH_VERSION_CODE = "anti_spy_launch_version_code"
        const val PREF_UNFREEZE_SHORTCUT_REGISTRY = "unfreeze_shortcut_registry"
        const val PREF_LEGACY_FROZEN_MIGRATION_DONE = "legacy_frozen_migration_done"
        /** Last seen work-profile package set; used to detect store installs between sessions. */
        const val PREF_KNOWN_WORK_PROFILE_PACKAGES = "known_work_profile_packages"
        /** User removed auto-freeze; do not re-assign until they enable it in the menu. */
        const val PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE = "auto_freeze_opt_out_work_profile"
        /** Store installs waiting for cross-profile write to the auto-freeze list. */
        const val PREF_PENDING_STORE_AUTO_FREEZE = "pending_store_auto_freeze"
        /** Last batch-freeze summary (VPN or manual) for diagnostics. */
        const val PREF_LAST_BATCH_FREEZE_AT = "last_batch_freeze_at"
        const val PREF_LAST_BATCH_FREEZE_NEWLY = "last_batch_freeze_newly"
        const val PREF_LAST_BATCH_FREEZE_STILL_VISIBLE = "last_batch_freeze_still_visible"
        const val PREF_LAST_BATCH_FREEZE_STILL_VISIBLE_PKGS = "last_batch_freeze_still_visible_pkgs"
        /** `:vpnwatch` poll heartbeat (main profile, written in main user). */
        const val PREF_VPN_WATCH_HEARTBEAT_MAIN = "vpn_watch_heartbeat_main"
        const val PREF_VPN_WATCH_VPN_MAIN = "vpn_watch_vpn_main"
        /** Work `:vpnwatch` heartbeat mirrored to main profile for Settings. */
        const val PREF_VPN_WATCH_HEARTBEAT_WORK_MIRROR = "vpn_watch_heartbeat_work_mirror"
        const val PREF_VPN_WATCH_VPN_WORK_MIRROR = "vpn_watch_vpn_work_mirror"
        /** Written in work profile user (local diagnostics). */
        const val PREF_VPN_WATCH_HEARTBEAT_WORK = "vpn_watch_heartbeat_work"
        const val PREF_VPN_WATCH_VPN_WORK = "vpn_watch_vpn_work"

        private const val LIST_DIVIDER = ","
        private const val PREFS_NAME = "prefs"

        private var instance: LocalStorageManager? = null

        fun initialize(context: Context) {
            instance = LocalStorageManager(context)
        }

        fun getInstance(): LocalStorageManager {
            return instance
                ?: throw IllegalStateException("LocalStorageManager must be initialized at start-up")
        }

        fun readStringListFresh(context: Context, pref: String): Array<String> =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(pref, "")!!
                .split(LIST_DIVIDER)
                .filter { it.isNotEmpty() }
                .toTypedArray()

        fun readBooleanFresh(context: Context, pref: String, defaultValue: Boolean): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(pref, defaultValue)
    }
}
