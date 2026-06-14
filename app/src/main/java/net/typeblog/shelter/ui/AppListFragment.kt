package net.typeblog.shelter.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.typeblog.shelter.util.ZindanToast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import net.typeblog.shelter.R
import net.typeblog.shelter.services.IAppInstallCallback
import net.typeblog.shelter.services.IGetAppsCallback
import net.typeblog.shelter.services.ILoadIconCallback
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.services.ShelterService
import net.typeblog.shelter.util.AntiSpyLaunchGate
import net.typeblog.shelter.util.AntiSpyManager
import net.typeblog.shelter.util.ApplicationInfoWrapper
import net.typeblog.shelter.util.AutoFreezeDefaults
import net.typeblog.shelter.util.AutoFreezePolicy
import net.typeblog.shelter.util.LocalStorageManager
import net.typeblog.shelter.util.Utility

class AppListFragment : BaseFragment() {
    private var service: IShelterService? = null
    private var isRemote = false
    private var refreshing = false
    private var defaultIcon: Drawable? = null
    private var selectedApp: ApplicationInfoWrapper? = null

    private val crossProfileWidgetProviders = HashSet<String>()
    private val crossProfilePackages = HashSet<String>()
    private var knownWorkProfilePackages: Set<String>? = null

    private var list: RecyclerView? = null
    private var adapter: AppListAdapter? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var actionMode: ActionMode? = null

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    private val searchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            var query = intent.getStringExtra("text")
            if ("" == query) {
                query = null
            }
            adapter!!.setSearchQuery(query)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        defaultIcon = requireActivity().packageManager.defaultActivityIcon
        val serviceBinder = requireArguments().getBinder("service")
        service = IShelterService.Stub.asInterface(serviceBinder)
        isRemote = requireArguments().getBoolean("is_remote")
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(refreshReceiver, IntentFilter(BROADCAST_REFRESH))
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(
                searchReceiver,
                IntentFilter(MainActivity.BROADCAST_SEARCH_FILTER_CHANGED)
            )
        refresh()
    }

    override fun onPause() {
        super.onPause()
        selectedApp = null
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(refreshReceiver)
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(searchReceiver)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)

        list = view.findViewById(R.id.fragment_list_recycler_view)
        swipeRefresh = view.findViewById(R.id.fragment_swipe_refresh)
        adapter = AppListAdapter(service!!, defaultIcon!!).apply {
            setContextMenuHandler(this@AppListFragment::showAppActionDialog)
            if (isRemote) {
                setWorkProfile(true)
                allowMultiSelect()
                setActionModeHandler(this@AppListFragment::createMultiSelectActionMode)
                setActionModeCancelHandler {
                    actionMode?.finish()
                }
            }
        }
        list!!.adapter = adapter
        list!!.layoutManager = LinearLayoutManager(activity)
        list!!.setHasFixedSize(true)

        swipeRefresh!!.setOnRefreshListener { refresh() }

        return view
    }

    private class AppMenuEntry(
        val itemId: Int,
        val label: CharSequence,
        val checkable: Boolean = false,
        val checked: Boolean = false
    ) {
        constructor(itemId: Int, label: CharSequence, checked: Boolean) :
            this(itemId, label, true, checked)
    }

    private fun showAppActionDialog(app: ApplicationInfoWrapper, anchor: View) {
        selectedApp = app
        val entries = buildAppMenuEntries(app)
        if (entries.isEmpty()) {
            selectedApp = null
            return
        }

        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val rowPad = (12 * density).toInt()
        val minRowHeight = (48 * density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(context).apply {
            text = getString(R.string.app_context_menu_title, app.getLabel())
            setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
            setTextColor(ContextCompat.getColor(context, R.color.zindanLightYellow))
            setPadding(0, 0, 0, rowPad)
        }
        layout.addView(title)

        val selectableBackground = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            selectableBackground, true
        )

        val dialog = AlertDialog.Builder(context).create()

        for (entry in entries) {
            val row = TextView(context).apply {
                text = if (entry.checkable) {
                    getString(
                        if (entry.checked) R.string.app_menu_check_on
                        else R.string.app_menu_check_off
                    ) + entry.label
                } else {
                    entry.label
                }
                minHeight = minRowHeight
                gravity = Gravity.CENTER_VERTICAL
                setTextAppearance(context, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
                setPadding(0, rowPad, 0, rowPad)
                setBackgroundResource(selectableBackground.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onAppMenuItemSelected(entry.itemId, app, entry.checked)
                }
            }
            layout.addView(row)
        }

        val scroll = ScrollView(context)
        scroll.addView(layout)
        dialog.setView(scroll)
        dialog.setOnDismissListener { selectedApp = null }
        dialog.show()
    }

    private fun buildAppMenuEntries(app: ApplicationInfoWrapper): List<AppMenuEntry> {
        val entries = ArrayList<AppMenuEntry>()
        val autoFreezeEnabled = AutoFreezePolicy.isInAutoFreezeList(app.getPackageName())
        if (isRemote) {
            if (!app.isSystem()) {
                entries.add(AppMenuEntry(MENU_ITEM_CLONE, getString(R.string.clone_to_main_profile)))
            }
            if (app.isHidden()) {
                entries.add(AppMenuEntry(MENU_ITEM_UNFREEZE, getString(R.string.unfreeze_app)))
                entries.add(AppMenuEntry(MENU_ITEM_LAUNCH, getString(R.string.launch)))
            } else {
                if (autoFreezeEnabled) {
                    entries.add(AppMenuEntry(MENU_ITEM_FREEZE, getString(R.string.freeze_app)))
                }
                entries.add(AppMenuEntry(MENU_ITEM_LAUNCH, getString(R.string.launch)))
            }
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_ALLOW_CROSS_PROFILE_WIDGET,
                    getString(R.string.allow_cross_profile_widgets),
                    crossProfileWidgetProviders.contains(app.getPackageName())
                )
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                entries.add(
                    AppMenuEntry(
                        MENU_ITEM_ALLOW_CROSS_PROFILE_INTERACTION,
                        getString(R.string.allow_cross_profile_interaction),
                        crossProfilePackages.contains(app.getPackageName())
                    )
                )
            }

            entries.add(
                AppMenuEntry(
                    MENU_ITEM_AUTO_FREEZE,
                    getString(R.string.auto_freeze),
                    LocalStorageManager.getInstance().stringListContains(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                        app.getPackageName()
                    )
                )
            )
            entries.add(
                AppMenuEntry(MENU_ITEM_CREATE_UNFREEZE_SHORTCUT, getString(R.string.create_unfreeze_shortcut))
            )
        } else {
            entries.add(AppMenuEntry(MENU_ITEM_CLONE, getString(R.string.clone_to_work_profile)))
        }

        if (!app.isSystem()) {
            entries.add(AppMenuEntry(MENU_ITEM_UNINSTALL, getString(R.string.uninstall_app)))
        }
        return entries
    }

    private fun onAppMenuItemSelected(itemId: Int, app: ApplicationInfoWrapper, checked: Boolean) {
        when (itemId) {
            MENU_ITEM_CLONE -> {
                val cloneAction = Runnable {
                    if (Utility.isMIUI() && !app.isSystem()) {
                        AlertDialog.Builder(requireContext())
                            .setMessage(R.string.miui_cannot_clone)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNegativeButton(R.string.continue_anyway) { _, _ ->
                                installOrUninstall(app, true)
                            }
                            .show()
                    } else {
                        installOrUninstall(app, true)
                    }
                }
                if (!isRemote) {
                    (activity as MainActivity).runAfterVpnGateCleared(
                        app.getPackageName(),
                        forceGate = true,
                        cloneAction
                    )
                } else {
                    cloneAction.run()
                }
            }
            MENU_ITEM_UNINSTALL -> installOrUninstall(app, false)
            MENU_ITEM_FREEZE -> {
                AntiSpyManager.syncAutoFreezeListToWorkProfile(requireContext())
                try {
                    service!!.freezeApp(app)
                } catch (_: RemoteException) {
                }
                ZindanToast.show(
                    requireContext(),
                    getString(R.string.freeze_success, app.getLabel()),
                )
                refresh()
            }
            MENU_ITEM_UNFREEZE -> {
                val unfreezeAction = Runnable {
                    try {
                        service!!.unfreezeApp(app)
                    } catch (_: RemoteException) {
                    }
                    ZindanToast.show(
                        requireContext(),
                        getString(R.string.unfreeze_success, app.getLabel()),
                    )
                    refresh()
                }
                if (AutoFreezePolicy.isInAutoFreezeList(app.getPackageName())) {
                    (activity as MainActivity).runAfterVpnGateCleared(
                        app.getPackageName(),
                        forceGate = false,
                        unfreezeAction
                    )
                } else {
                    unfreezeAction.run()
                }
            }
            MENU_ITEM_LAUNCH -> {
                val intent = Intent(DummyActivity.UNFREEZE_AND_LAUNCH).apply {
                    component = ComponentName(requireContext(), DummyActivity::class.java)
                    putExtra("packageName", app.getPackageName())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                DummyActivity.registerSameProcessRequest(intent)
                startActivity(intent)
            }
            MENU_ITEM_CREATE_UNFREEZE_SHORTCUT -> loadIconAndAddUnfreezeShortcut(app, null)
            MENU_ITEM_AUTO_FREEZE -> {
                if (!checked) {
                    AutoFreezeDefaults.enableForWorkProfile(requireContext(), app.getPackageName())
                } else {
                    LocalStorageManager.getInstance().removeFromStringList(
                        LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE, app.getPackageName()
                    )
                    AntiSpyManager.syncAutoFreezeListToWorkProfile(requireContext())
                    if (app.isHidden()) {
                        try {
                            service!!.unfreezeApp(app)
                        } catch (_: RemoteException) {
                        }
                        ZindanToast.show(
                            requireContext(),
                            getString(R.string.unfreeze_success, app.getLabel()),
                        )
                    }
                }
                LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(Intent(BROADCAST_REFRESH))
            }
            MENU_ITEM_ALLOW_CROSS_PROFILE_WIDGET -> {
                val newState = !checked
                try {
                    if (service!!.setCrossProfileWidgetProviderEnabled(app.getPackageName(), newState)) {
                        if (newState) {
                            crossProfileWidgetProviders.add(app.getPackageName())
                        } else {
                            crossProfileWidgetProviders.remove(app.getPackageName())
                        }
                    }
                } catch (_: RemoteException) {
                }
            }
            MENU_ITEM_ALLOW_CROSS_PROFILE_INTERACTION -> {
                val newState = !checked
                if (newState) {
                    crossProfilePackages.add(app.getPackageName())
                } else {
                    crossProfilePackages.remove(app.getPackageName())
                }
                try {
                    service!!.setCrossProfilePackages(ArrayList(crossProfilePackages))
                } catch (_: RemoteException) {
                }
            }
        }
    }

    fun refresh() {
        if (adapter == null) return
        if (refreshing) return
        if (adapter!!.isMultiSelectMode()) {
            swipeRefresh!!.isRefreshing = false
            return
        }
        refreshing = true
        swipeRefresh!!.isRefreshing = true

        try {
            service!!.getApps(object : IGetAppsCallback.Stub() {
                override fun callback(apps: MutableList<ApplicationInfoWrapper>) {
                    if (isRemote) {
                        crossProfileWidgetProviders.clear()
                        crossProfilePackages.clear()

                        try {
                            crossProfileWidgetProviders.addAll(service!!.crossProfileWidgetProviders)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                crossProfilePackages.addAll(service!!.crossProfilePackages)
                            }
                        } catch (_: RemoteException) {
                        }
                    }

                    var autoFreezePackages: Set<String>? = null
                    if (isRemote) {
                        val currentPackages = apps.map { it.getPackageName() }
                        val currentSet = HashSet(currentPackages)
                        if (knownWorkProfilePackages != null) {
                            val removed = knownWorkProfilePackages!!.filter { it !in currentSet }
                            for (pkg in removed) {
                                Utility.removeUnfreezeLauncherShortcutsEverywhere(requireContext(), pkg)
                                LocalStorageManager.getInstance().removeFromStringList(
                                    LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                                    pkg
                                )
                            }
                        }
                        Utility.deleteMissingApps(
                            LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                            apps
                        )
                        AutoFreezeDefaults.applyDefaultsForNewPackages(
                            requireContext(),
                            knownWorkProfilePackages, currentPackages
                        )
                        knownWorkProfilePackages = currentSet
                        AntiSpyManager.syncAutoFreezeListToWorkProfile(requireContext())
                        AutoFreezePolicy.migrateLegacyFrozenWithoutAutoFreeze(service!!, apps)
                        autoFreezePackages = HashSet(
                            LocalStorageManager.getInstance()
                                .getStringList(LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE)
                                .toList()
                        )
                    }
                    val freezePackages = autoFreezePackages
                    runOnUiThread {
                        swipeRefresh!!.isRefreshing = false
                        adapter!!.setData(apps)
                        if (freezePackages != null) {
                            adapter!!.setAutoFreezePackages(freezePackages)
                        }
                        refreshing = false
                    }
                }
            }, (activity as MainActivity).showAll)
        } catch (_: RemoteException) {
        }
    }

    fun createMultiSelectActionMode(): Boolean {
        actionMode = (activity as AppCompatActivity).startSupportActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_ITEM_CREATE_UNFREEZE_SHORTCUT, Menu.NONE, R.string.create_unfreeze_shortcut)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.title = getString(R.string.batch_operation)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val selectedList = adapter!!.getSelectedItems() ?: return false

                when (item.itemId) {
                    MENU_ITEM_CREATE_UNFREEZE_SHORTCUT -> {
                        loadIconAndAddUnfreezeShortcut(selectedList[0], selectedList)
                        mode.finish()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                adapter!!.cancelMultiSelectMode()
            }
        })
        return true
    }

    fun installOrUninstall(app: ApplicationInfoWrapper, isInstall: Boolean) {
        selectedApp = null
        val callback = object : IAppInstallCallback.Stub() {
            override fun callback(result: Int) {
                runOnUiThread { installAppCallback(result, app, isInstall) }
            }
        }

        try {
            if (isInstall) {
                (activity as MainActivity).getOtherService(isRemote)
                    .installApp(app, callback)
            } else {
                service!!.uninstallApp(app, callback)
            }
        } catch (_: RemoteException) {
        }
    }

    fun installAppCallback(result: Int, app: ApplicationInfoWrapper, isInstall: Boolean) {
        if (result == Activity.RESULT_OK) {
            var message = getString(if (isInstall) R.string.clone_success else R.string.uninstall_success)
            message = String.format(message, app.getLabel())
            ZindanToast.show(requireContext(), message)
            if (isInstall && !isRemote) {
                AutoFreezeDefaults.enableForWorkProfile(requireContext(), app.getPackageName())
            }
            if (!isInstall && isRemote) {
                Utility.removeUnfreezeLauncherShortcutsEverywhere(
                    requireContext(),
                    app.getPackageName()
                )
                LocalStorageManager.getInstance().removeFromStringList(
                    LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                    app.getPackageName()
                )
            }
            LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(Intent(BROADCAST_REFRESH))
        } else if (result == ShelterService.RESULT_CANNOT_INSTALL_SYSTEM_APP) {
            ZindanToast.show(
                requireContext(),
                getString(
                    if (isInstall) R.string.clone_fail_system_app else R.string.uninstall_fail_system_app
                ),
            )
        }
    }

    fun loadIconAndAddUnfreezeShortcut(
        app: ApplicationInfoWrapper,
        linkedApps: List<ApplicationInfoWrapper>?
    ) {
        try {
            service!!.loadIcon(app, object : ILoadIconCallback.Stub() {
                override fun callback(icon: Bitmap) {
                    runOnUiThread { addUnfreezeShortcut(app, linkedApps, icon) }
                }
            })
        } catch (_: RemoteException) {
        }
    }

    fun addUnfreezeShortcut(
        app: ApplicationInfoWrapper,
        linkedApps: List<ApplicationInfoWrapper>?,
        icon: Bitmap
    ) {
        val linkedPackages = linkedApps?.joinToString(",") { it.getPackageName() }
        val launchIntent = Utility.buildUnfreezeShortcutLaunchIntent(
            requireContext(), app.getPackageName(), linkedPackages
        )
        val id = Utility.unfreezeShortcutId(app.getPackageName(), linkedPackages)
        Utility.createLauncherShortcut(
            requireContext(), launchIntent,
            Icon.createWithBitmap(icon), id,
            app.getLabel() ?: app.getPackageName()
        )
        Utility.registerUnfreezeShortcut(
            app.getPackageName(),
            id,
            app.getLabel() ?: app.getPackageName(),
            linkedPackages
        )
    }

    companion object {
        const val BROADCAST_REFRESH = "net.typeblog.shelter.broadcast.REFRESH"

        private const val MENU_ITEM_CLONE = 10001
        private const val MENU_ITEM_UNINSTALL = 10002
        private const val MENU_ITEM_FREEZE = 10003
        private const val MENU_ITEM_UNFREEZE = 10004
        private const val MENU_ITEM_LAUNCH = 10005
        private const val MENU_ITEM_CREATE_UNFREEZE_SHORTCUT = 10006
        private const val MENU_ITEM_AUTO_FREEZE = 10007
        private const val MENU_ITEM_ALLOW_CROSS_PROFILE_WIDGET = 10008
        private const val MENU_ITEM_ALLOW_CROSS_PROFILE_INTERACTION = 10009

        fun newInstance(service: IShelterService, isRemote: Boolean): AppListFragment {
            return AppListFragment().apply {
                arguments = Bundle().apply {
                    putBinder("service", service.asBinder())
                    putBoolean("is_remote", isRemote)
                }
            }
        }
    }
}
