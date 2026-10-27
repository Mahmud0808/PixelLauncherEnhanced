package com.drdisagree.pixellauncherenhanced.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHORTCUTS_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHORTCUTS_RESPONSE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHORTCUT_LABELS
import com.drdisagree.pixellauncherenhanced.data.common.ShortcutCatalog
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.iconpack.IconPackManager
import com.drdisagree.pixellauncherenhanced.data.model.AppInfoModel
import com.drdisagree.pixellauncherenhanced.ui.fragments.HiddenApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

object ShortcutPicker {

    private const val RESPONSE_TIMEOUT_MS = 4000L
    private const val INDENT_DP = 28
    private const val BASE_PADDING_DP = 12

    fun label(context: Context, target: String?): String? {
        if (!ShortcutCatalog.isValidTarget(target)) return null
        val packageName = ShortcutCatalog.targetPackage(target)
        val appLabel = packageName?.let { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }
                .getOrNull() ?: pkg
        }
        val shortcutLabel = ShortcutCatalog.parseLabels(RPrefs.getString(SHORTCUT_LABELS))[target]
            ?: ShortcutCatalog.decodeTarget(target)?.id
            ?: context.getString(R.string.gesture_action_open_shortcut)
        return if (appLabel != null) context.getString(R.string.shortcut_target_label, appLabel, shortcutLabel) else shortcutLabel
    }

    fun show(context: Context, scope: LifecycleCoroutineScope, onPicked: (String) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null)
        val list = view.findViewById<RecyclerView>(R.id.app_list)
        val search = view.findViewById<EditText>(R.id.search)
        val progress = view.findViewById<View>(R.id.progress)
        list.layoutManager = LinearLayoutManager(context)

        val handler = Handler(Looper.getMainLooper())
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        fun finish(target: String, label: String) {
            val labels = ShortcutCatalog.parseLabels(RPrefs.getString(SHORTCUT_LABELS)).toMutableMap()
            labels[target] = label
            RPrefs.putString(SHORTCUT_LABELS, ShortcutCatalog.serializeLabels(labels))
            onPicked(target)
            dialog.dismiss()
        }

        val adapter = PickerAdapter(
            context = context,
            request = { packageName -> request(packageName) },
            onDeep = { packageName, entry -> finish(ShortcutCatalog.Target(packageName, entry.id).encode(), entry.label) },
            onCreator = { creator -> createLegacyShortcut(context, creator) { target, label -> finish(target, label) } }
        )

        var pendingToken = 0L
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != SHORTCUTS_RESPONSE) return@OnSharedPreferenceChangeListener
            val response = ShortcutCatalog.decodeResponse(RPrefs.getString(SHORTCUTS_RESPONSE))
                ?: return@OnSharedPreferenceChangeListener
            handler.post {
                when (response) {
                    is ShortcutCatalog.Response.Counts -> if (response.token == pendingToken) {
                        pendingToken = 0L
                        progress.visibility = View.GONE
                        adapter.setDeepCounts(response.counts)
                    }

                    is ShortcutCatalog.Response.Shortcuts -> adapter.setDeepShortcuts(response.packageName, response.entries)
                }
            }
        }
        RPrefs.registerOnSharedPreferenceChangeListener(listener)

        dialog = ExpressiveDialog.show(
            context = context,
            icon = R.drawable.ic_open,
            title = context.getString(R.string.gesture_choose_shortcut),
            content = view,
            fullWidthContent = true,
            negative = ExpressiveDialog.Action(context.getString(android.R.string.cancel)),
            onDismiss = {
                RPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                handler.removeCallbacksAndMessages(null)
            }
        )
        list.adapter = adapter
        search.doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        scope.launch {
            val (apps, creators) = withContext(Dispatchers.IO) {
                val apps = HiddenApps.getAllLaunchableApps(emptySet()).distinctBy { it.packageName }
                val creators = context.packageManager
                    .queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
                    .mapNotNull { it.activityInfo }
                    .filter { it.exported }
                    .groupBy { it.packageName }
                apps to creators
            }
            adapter.setApps(apps, creators)
            if (creators.isNotEmpty()) progress.visibility = View.GONE
            pendingToken = request(null)
            handler.postDelayed({
                if (pendingToken != 0L && dialog.isShowing) {
                    progress.visibility = View.GONE
                    if (creators.isEmpty()) Toast.makeText(context, R.string.shortcuts_unavailable, Toast.LENGTH_LONG).show()
                }
            }, RESPONSE_TIMEOUT_MS)
        }
    }

    private fun request(packageName: String?): Long {
        val token = System.nanoTime()
        RPrefs.putString(SHORTCUTS_REQUEST, ShortcutCatalog.encodeRequest(ShortcutCatalog.Request(token, packageName)))
        return token
    }

    private fun createLegacyShortcut(context: Context, creator: ActivityInfo, onCreated: (String, String) -> Unit) {
        val activity = context as? ComponentActivity ?: return
        val key = "shortcut_creator_${System.nanoTime()}"
        var launcher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
        launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) { result ->
            launcher?.unregister()
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) return@register
            @Suppress("DEPRECATION")
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
            } else {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
            }
            if (intent == null) {
                Toast.makeText(context, R.string.shortcut_not_supported, Toast.LENGTH_LONG).show()
                return@register
            }
            @Suppress("DEPRECATION")
            val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
                ?: creator.loadLabel(context.packageManager).toString()
            if (intent.`package` == null && intent.component == null) intent.`package` = creator.packageName
            onCreated(ShortcutCatalog.encodeIntent(intent), name)
        }
        runCatching {
            launcher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(ComponentName(creator.packageName, creator.name)))
        }.onFailure {
            launcher.unregister()
            Toast.makeText(context, R.string.shortcut_not_supported, Toast.LENGTH_LONG).show()
        }
    }

    private sealed class Row(val key: String) {
        class App(val app: AppInfoModel, val count: Int, val expanded: Boolean, val loading: Boolean) :
            Row("app:${app.packageName}")
        class Deep(val packageName: String, val entry: ShortcutCatalog.Entry, val icon: Drawable?) :
            Row("deep:$packageName/${entry.id}")

        class Creator(val creator: ActivityInfo, val label: String, val icon: Drawable?) :
            Row("creator:${creator.packageName}/${creator.name}")
    }

    private object RowDiff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.App && newItem is Row.App -> oldItem.count == newItem.count &&
                    oldItem.expanded == newItem.expanded && oldItem.loading == newItem.loading
            oldItem is Row.Deep && newItem is Row.Deep -> oldItem.entry == newItem.entry
            else -> true
        }
    }

    private class PickerAdapter(
        private val context: Context,
        private val request: (String) -> Unit,
        private val onDeep: (String, ShortcutCatalog.Entry) -> Unit,
        private val onCreator: (ActivityInfo) -> Unit
    ) : ListAdapter<Row, RecyclerView.ViewHolder>(RowDiff) {

        private var apps: List<AppInfoModel> = emptyList()
        private var creators: Map<String, List<ActivityInfo>> = emptyMap()
        private var deepCounts: Map<String, Int> = emptyMap()
        private val deepShortcuts = HashMap<String, List<Pair<ShortcutCatalog.Entry, Drawable?>>>()
        private val requested = HashSet<String>()
        private val expanded = HashSet<String>()
        private val creatorLabels = HashMap<String, Pair<String, Drawable?>>()
        private var query = ""

        fun setApps(list: List<AppInfoModel>, legacy: Map<String, List<ActivityInfo>>) {
            apps = list
            creators = legacy
            rebuild()
        }

        fun setDeepCounts(map: Map<String, Int>) {
            deepCounts = map
            rebuild()
        }

        fun setDeepShortcuts(packageName: String, entries: List<ShortcutCatalog.Entry>) {
            deepShortcuts[packageName] = entries.map { entry ->
                entry to IconPackManager.decodeBitmap(entry.icon)?.let { BitmapDrawable(context.resources, it) }
            }
            rebuild()
        }

        fun filter(text: String) {
            query = text.trim().lowercase(Locale.getDefault())
            rebuild()
        }

        private fun creatorInfo(info: ActivityInfo): Pair<String, Drawable?> =
            creatorLabels.getOrPut("${info.packageName}/${info.name}") {
                info.loadLabel(context.packageManager).toString() to runCatching {
                    IconPackManager.renderShortcutIcon(info.loadIcon(context.packageManager), iconSize())
                        .let { BitmapDrawable(context.resources, it) }
                }.getOrNull()
            }

        private fun iconSize() = (40 * context.resources.displayMetrics.density).toInt()

        private fun matches(text: String) = query.isEmpty() || text.lowercase(Locale.getDefault()).contains(query)

        private fun rebuild() {
            val rows = buildList {
                apps.forEach { app ->
                    val pkg = app.packageName
                    val legacy = creators[pkg].orEmpty()
                    val count = (deepCounts[pkg] ?: 0) + legacy.size
                    if (count == 0) return@forEach
                    val children = deepShortcuts[pkg].orEmpty().map { it.first.label } + legacy.map { creatorInfo(it).first }
                    if (!matches(app.appName) && children.none { matches(it) }) return@forEach

                    val open = pkg in expanded
                    val loading = open && (deepCounts[pkg] ?: 0) > 0 && deepShortcuts[pkg] == null
                    add(Row.App(app, count, open, loading))
                    if (!open) return@forEach
                    legacy.forEach { info ->
                        val (label, icon) = creatorInfo(info)
                        add(Row.Creator(info, label, icon))
                    }
                    deepShortcuts[pkg]?.forEach { (entry, icon) -> add(Row.Deep(pkg, entry, icon)) }
                }
            }
            submitList(rows)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.view_shortcut_picker_item, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            val icon = view.findViewById<ImageView>(R.id.app_icon)
            val title = view.findViewById<TextView>(R.id.title)
            val summary = view.findViewById<TextView>(R.id.summary)
            val loading = view.findViewById<View>(R.id.loading)
            loading.visibility = if ((getItem(position) as? Row.App)?.loading == true) View.VISIBLE else View.GONE
            val density = context.resources.displayMetrics.density
            val padding = (BASE_PADDING_DP * density).toInt()
            val indent = (INDENT_DP * density).toInt()
            fun indentBy(extra: Int) = view.setPaddingRelative(padding + extra, view.paddingTop, padding, view.paddingBottom)

            when (val row = getItem(position)) {
                is Row.App -> {
                    indentBy(0)
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(row.app.appIcon)
                    title.text = row.app.appName
                    summary.visibility = View.VISIBLE
                    summary.text = if (row.count == 1) {
                        context.getString(R.string.shortcut_count_one)
                    } else {
                        context.getString(R.string.shortcut_count, row.count)
                    }
                    view.setOnClickListener {
                        val pkg = row.app.packageName
                        if (!expanded.add(pkg)) {
                            expanded.remove(pkg)
                        } else if ((deepCounts[pkg] ?: 0) > 0 && requested.add(pkg)) {
                            request(pkg)
                        }
                        rebuild()
                    }
                }

                is Row.Deep -> {
                    indentBy(indent)
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(row.icon)
                    title.text = row.entry.label
                    summary.visibility = View.GONE
                    view.setOnClickListener { onDeep(row.packageName, row.entry) }
                }

                is Row.Creator -> {
                    indentBy(indent)
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(row.icon)
                    title.text = row.label
                    summary.visibility = View.GONE
                    view.setOnClickListener { onCreator(row.creator) }
                }

            }
        }
    }
}
