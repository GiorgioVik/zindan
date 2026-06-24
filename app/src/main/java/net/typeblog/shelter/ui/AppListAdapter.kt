package net.typeblog.shelter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.os.RemoteException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import net.typeblog.shelter.R
import net.typeblog.shelter.services.ILoadIconCallback
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.util.ApplicationInfoWrapper

class AppListAdapter(
    private val service: IShelterService,
    private val defaultIcon: Drawable
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.list_app_icon)
        private val title: TextView = view.findViewById(R.id.list_app_title)
        private val pkg: TextView = view.findViewById(R.id.list_app_package)
        private val autoFreezeBadge: ImageView = view.findViewById(R.id.list_app_auto_freeze)
        private val selectOrder: TextView = view.findViewById(R.id.list_app_select_order)
        private var itemIndex = -1

        init {
            view.setOnClickListener { onClick() }
            if (allowMultiSelect) {
                view.setOnLongClickListener { onLongClick() }
            }
        }

        private fun onClick() {
            if (itemIndex == -1) return

            if (!multiSelectMode) {
                contextMenuHandler?.showContextMenu(list[itemIndex], itemView)
            } else {
                if (!selectedIndices.contains(itemIndex)) {
                    select()
                } else {
                    deselect()
                }
            }
        }

        private fun onLongClick(): Boolean {
            if (itemIndex == -1) return false

            if (!multiSelectMode && actionModeHandler?.createActionMode() == true) {
                multiSelectMode = true
                select()
                return true
            }
            return false
        }

        fun select() {
            selectedIndices.add(itemIndex)
            selectOrder.clearAnimation()
            selectOrder.startAnimation(
                AnimationUtils.loadAnimation(itemView.context, R.anim.scale_appear)
            )
            showSelectOrder()
        }

        fun deselect() {
            selectedIndices.remove(itemIndex)
            selectOrder.clearAnimation()
            setUnselectedBackground()
            val anim = AnimationUtils.loadAnimation(itemView.context, R.anim.scale_hide)
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    if (actionModeCancelHandler != null && selectedIndices.isEmpty()) {
                        actionModeCancelHandler!!.cancelActionMode()
                    }
                    notifyDataSetChanged()
                }
                override fun onAnimationRepeat(animation: Animation) {}
            })
            selectOrder.startAnimation(anim)
        }

        fun showSelectOrder() {
            if (!list[itemIndex].isHidden()) {
                itemView.setBackgroundResource(R.color.selectedAppBackground)
            } else {
                itemView.setBackgroundResource(R.color.selectedAndDisabledAppBackground)
            }
            updateTextColors(list[itemIndex])
            selectOrder.visibility = View.VISIBLE
            selectOrder.text = (selectedIndices.indexOf(itemIndex) + 1).toString()
        }

        fun hideSelectOrder() {
            setUnselectedBackground()
            updateTextColors(list[itemIndex])
            selectOrder.visibility = View.GONE
        }

        private fun updateTextColors(info: ApplicationInfoWrapper) {
            val onDarkBackground = info.isHidden() ||
                (multiSelectMode && selectedIndices.contains(itemIndex)) ||
                isDarkListBackground(itemView.context)
            if (onDarkBackground) {
                title.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.colorTextPrimary),
                )
                pkg.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.colorTextSecondary),
                )
            } else {
                title.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.colorTextOnLight),
                )
                pkg.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.colorTextSecondary),
                )
            }
        }

        private fun isDarkListBackground(context: Context): Boolean {
            val primary = ContextCompat.getColor(context, R.color.colorPrimary)
            val r = android.graphics.Color.red(primary) / 255f
            val g = android.graphics.Color.green(primary) / 255f
            val b = android.graphics.Color.blue(primary) / 255f
            val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
            return luminance < 0.4f
        }

        private fun setUnselectedBackground() {
            if (!list[itemIndex].isHidden()) {
                itemView.background = null
            } else {
                itemView.setBackgroundResource(R.color.disabledAppBackground)
            }
        }

        fun setIndex(itemIndex: Int) {
            this.itemIndex = itemIndex

            if (itemIndex >= 0) {
                selectOrder.clearAnimation()

                val info = list[itemIndex]
                pkg.text = info.getPackageName()
                updateTextColors(info)

                if (info.isHidden()) {
                    title.text = String.format(labelDisabled!!, info.getLabel())
                } else {
                    title.text = info.getLabel()
                }

                autoFreezeBadge.visibility = if (workProfile && autoFreezePackages.contains(info.getPackageName())) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                if (multiSelectMode && selectedIndices.contains(itemIndex)) {
                    showSelectOrder()
                } else {
                    hideSelectOrder()
                }

                val cachedIcon = iconCache.get(info.getPackageName())
                if (cachedIcon != null) {
                    icon.setImageBitmap(cachedIcon)
                } else {
                    icon.setImageDrawable(defaultIcon)
                    try {
                        service.loadIcon(info, object : ILoadIconCallback.Stub() {
                            override fun callback(iconBitmap: Bitmap) {
                                if (itemIndex == this@ViewHolder.itemIndex) {
                                    handler.post { icon.setImageBitmap(iconBitmap) }
                                }
                                synchronized(AppListAdapter::class.java) {
                                    iconCache.put(info.getPackageName(), iconBitmap)
                                }
                            }
                        })
                    } catch (_: RemoteException) {
                    }
                }
            }
        }
    }

    fun interface ContextMenuHandler {
        fun showContextMenu(info: ApplicationInfoWrapper, view: View)
    }

    fun interface ActionModeHandler {
        fun createActionMode(): Boolean
    }

    fun interface ActionModeCancelHandler {
        fun cancelActionMode()
    }

    private val origList = ArrayList<ApplicationInfoWrapper>()
    private val list = ArrayList<ApplicationInfoWrapper>()
    private var searchQuery: String? = null
    private val iconCache = object : LruCache<String, Bitmap>(MAX_ICON_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                value.allocationByteCount
            } else {
                @Suppress("DEPRECATION")
                value.byteCount
            }
            return (bytes / 1024).coerceAtLeast(1)
        }
    }
    private var contextMenuHandler: ContextMenuHandler? = null
    private var actionModeHandler: ActionModeHandler? = null
    private var actionModeCancelHandler: ActionModeCancelHandler? = null
    private val handler = Handler(Looper.getMainLooper())
    private var workProfile = false
    private var autoFreezePackages: Set<String> = emptySet()
    private var allowMultiSelect = false
    private var multiSelectMode = false
    private val selectedIndices = ArrayList<Int>()
    private var labelDisabled: String? = null

    fun setWorkProfile(workProfile: Boolean) {
        this.workProfile = workProfile
    }

    fun setAutoFreezePackages(packages: Set<String>?) {
        autoFreezePackages = packages ?: emptySet()
        notifyDataSetChanged()
    }

    fun setContextMenuHandler(handler: ContextMenuHandler?) {
        contextMenuHandler = handler
    }

    fun setActionModeHandler(handler: ActionModeHandler?) {
        actionModeHandler = handler
    }

    fun setActionModeCancelHandler(handler: ActionModeCancelHandler?) {
        actionModeCancelHandler = handler
    }

    fun allowMultiSelect() {
        allowMultiSelect = true
    }

    fun isMultiSelectMode(): Boolean = multiSelectMode

    fun cancelMultiSelectMode() {
        multiSelectMode = false
        selectedIndices.clear()
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<ApplicationInfoWrapper>? {
        if (!multiSelectMode) return null
        if (selectedIndices.isEmpty()) return null
        return selectedIndices.map { list[it] }
    }

    fun setData(apps: List<ApplicationInfoWrapper>) {
        origList.clear()
        list.clear()
        iconCache.evictAll()
        origList.addAll(apps)
        notifyChange()
    }

    fun setSearchQuery(query: String?) {
        searchQuery = query
        notifyChange()
    }

    private fun notifyChange() {
        list.clear()
        if (searchQuery == null) {
            list.addAll(origList)
        } else {
            list.addAll(
                origList.filter { app ->
                    app.getPackageName().lowercase().contains(searchQuery!!) ||
                        app.getLabel()!!.lowercase().contains(searchQuery!!)
                }
            )
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = list.size

    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): ViewHolder {
        if (labelDisabled == null) {
            labelDisabled = viewGroup.context.getString(R.string.list_item_disabled)
        }
        val inflater = LayoutInflater.from(viewGroup.context)
        val view = inflater.inflate(R.layout.app_list_item, viewGroup, false)
        return ViewHolder(view).also { it.setIndex(i) }
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, i: Int) {
        viewHolder.setIndex(i)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.setIndex(-1)
    }

    companion object {
        private const val MAX_ICON_CACHE_KB = 8192
    }
}
