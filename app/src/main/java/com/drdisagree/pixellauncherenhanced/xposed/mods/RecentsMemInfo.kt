package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_MEMINFO
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_MEMINFO_CHIP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RECENTS_MEMINFO_ZRAM
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.connectRootProxy
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

class RecentsMemInfo(context: Context) : ModPack(context) {

    private var showMemInfo = false
    private var showZram = false
    private var showChip = false
    private var memInfoViewRef: WeakReference<MemInfoView>? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            showMemInfo = getBoolean(RECENTS_MEMINFO, false)
            showZram = getBoolean(RECENTS_MEMINFO_ZRAM, false)
            showChip = getBoolean(RECENTS_MEMINFO_CHIP, false)
        }

        when (key.firstOrNull()) {
            RECENTS_MEMINFO,
            RECENTS_MEMINFO_ZRAM,
            RECENTS_MEMINFO_CHIP -> memInfoViewRef?.get()?.applyPrefs()
        }

        if (showMemInfo) connectRootProxy()
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val overviewActionsViewClass = findClass("com.android.quickstep.views.OverviewActionsView")

        overviewActionsViewClass
            .hookMethod("onFinishInflate")
            .runAfter { param ->
                val container = param.thisObject as? FrameLayout ?: return@runAfter
                val view = MemInfoView(container.context, container)
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    )
                )
                memInfoViewRef = WeakReference(view)
                view.applyPrefs()
            }

        overviewActionsViewClass
            .hookMethod("updateVerticalMargin")
            .suppressError()
            .runAfter { memInfoViewRef?.get()?.updateVerticalMargin() }
    }

    @SuppressLint("ViewConstructor")
    private inner class MemInfoView(context: Context, private val container: View) : TextView(context) {

        private val activityManager = context.getSystemService(ActivityManager::class.java)
        private val memoryInfo = ActivityManager.MemoryInfo()
        private val totalResult = formatTotalMemory()
        private val textColor = overviewButtonColor()
        private var handler: Handler? = null
        private val worker = MemoryWorker(this)

        init {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            visibility = GONE
            setOnClickListener { openRunningServices() }
        }

        fun applyPrefs() {
            visibility = if (showMemInfo) INVISIBLE else GONE
            syncWithActionButtons()
            background = if (showChip) actionButtonBackground() ?: chipBackground() else null
            val horizontal = if (showChip) dp(CHIP_PADDING_HORIZONTAL_DP) else 0
            val vertical = if (showChip) dp(CHIP_PADDING_VERTICAL_DP) else 0
            setPadding(horizontal, vertical, horizontal, vertical)
            updateVerticalMargin()
            if (isAggregatedVisible) restartMonitoring()
        }

        fun updateVerticalMargin() {
            val params = layoutParams as? FrameLayout.LayoutParams ?: return
            val deviceProfile = container.getFieldSilently("mDp")
            val claimedSpace = deviceProfile.callMethodSilently("getOverviewActionsClaimedSpaceBelow") as? Int
                ?: container.rootWindowInsets?.stableInsetBottom
                ?: 0

            params.bottomMargin = claimedSpace + dp(BOTTOM_GAP_DP)
            params.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            layoutParams = params
        }

        private var isAggregatedVisible = false

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            isAggregatedVisible = isVisible
            if (isVisible && showMemInfo) startMonitoring() else stopMonitoring()
        }

        private val actionButtons: View?
            get() = container.getFieldSilently("mActionButtons") as? View

        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            syncWithActionButtons()
            true
        }

        private fun syncWithActionButtons() {
            if (!showMemInfo) return
            val buttons = actionButtons ?: return

            val target = if (buttons.visibility == VISIBLE && buttons.alpha > 0f) VISIBLE else INVISIBLE
            if (visibility != target) visibility = target
            if (alpha != buttons.alpha) alpha = buttons.alpha
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            stopMonitoring()
            super.onDetachedFromWindow()
        }

        private fun restartMonitoring() {
            stopMonitoring()
            if (showMemInfo) startMonitoring()
        }

        private fun startMonitoring() {
            if (handler != null) return
            handler = Handler(workerLooper()).also { it.post(worker) }
        }

        private fun stopMonitoring() {
            handler?.removeCallbacks(worker)
            handler = null
        }

        private fun formatTotalMemory(): String {
            activityManager?.getMemoryInfo(memoryInfo)
            val totalGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            return "${roundToKnownRamSize(totalGb)} GB"
        }

        private fun roundToKnownRamSize(memoryGb: Double): Int {
            if (memoryGb <= 0) return 1
            return KNOWN_RAM_SIZES.firstOrNull { memoryGb <= it } ?: KNOWN_RAM_SIZES.last()
        }

        private fun freeMemory(): Long {
            val meminfo = readMemInfo()
            val free = meminfo["MemFree"] ?: 0L
            val cached = (meminfo["Buffers"] ?: 0L) +
                    (meminfo["Cached"] ?: 0L) +
                    (meminfo["KReclaimable"] ?: meminfo["SReclaimable"] ?: 0L) -
                    (meminfo["Mapped"] ?: 0L)

            return (free + cached.coerceAtLeast(0L)) * 1024L + totalBackgroundMemory()
        }

        private fun readMemInfo(): Map<String, Long> = runCatching {
            File("/proc/meminfo").readLines().mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                val name = parts.getOrNull(0)?.removeSuffix(":") ?: return@mapNotNull null
                val value = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                name to value
            }.toMap()
        }.getOrDefault(emptyMap())

        private fun totalBackgroundMemory(): Long {
            val processes = runCatching { activityManager?.runningAppProcesses }.getOrNull().orEmpty()
            if (processes.isEmpty()) return 0L

            val memoryInfos = runCatching {
                activityManager?.getProcessMemoryInfo(processes.map { it.pid }.toIntArray())
            }.getOrNull() ?: return 0L

            return processes.indices.sumOf { index ->
                if (processes[index].importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                    (memoryInfos.getOrNull(index)?.totalPss ?: 0) * 1024L
                } else 0L
            }
        }

        private fun zramSize(): Long {
            if (!showZram) return 0L

            runCatching { File("/sys/block/zram0/disksize").readText().trim().toLong() }
                .getOrNull()
                ?.takeIf { it > 0 }
                ?.let { return it }

            return runCatching {
                File("/proc/swaps").readLines()
                    .firstOrNull { it.contains("zram0") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(2)
                    ?.toLongOrNull()
                    ?.times(1024L)
            }.getOrNull() ?: 0L
        }

        fun buildText(): String {
            val available = Formatter.formatShortFileSize(context, freeMemory())
            val zram = zramSize()
            val total = if (zram > 0) "$totalResult + ${Formatter.formatShortFileSize(context, zram)}" else totalResult
            return String.format(Locale.getDefault(), modRes.getString(R.string.meminfo_text), available, total)
        }

        fun postText(text: String) {
            post { setText(text) }
        }

        fun reschedule(task: Runnable) {
            handler?.let {
                it.removeCallbacks(task)
                it.postDelayed(task, REFRESH_INTERVAL_MS)
            }
        }

        val isMonitoring: Boolean
            get() = handler != null

        private fun openRunningServices() {
            enqueueProxyCommand { proxy ->
                val output = proxy.runCommand(RUNNING_SERVICES_COMMAND).joinToString(" ")
                if (output.contains("Error", ignoreCase = true) || output.contains("Exception")) {
                    post { openAppList() }
                }
            }
        }

        private fun openAppList() {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (_: ActivityNotFoundException) {
            }
        }

        @SuppressLint("DiscouragedApi")
        private fun overviewButtonColor(): Int {
            val id = context.resources.getIdentifier("overview_button", "color", context.packageName)
            return if (id != 0) context.getColor(id) else currentTextColor
        }

        private fun actionButtonBackground(): Drawable? {
            val buttons = actionButtons as? ViewGroup ?: return null
            val reference = (0 until buttons.childCount)
                .map { buttons.getChildAt(it) }
                .firstOrNull { it is Button && it.background != null }
                ?: return null

            return reference.background.constantState?.newDrawable(resources)?.mutate()
        }

        private fun chipBackground() = GradientDrawable().apply {
            cornerRadius = dp(CHIP_RADIUS_DP).toFloat()
            setColor(ColorUtils.setAlphaComponent(textColor, CHIP_ALPHA))
        }

        private fun dp(value: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }

    private class MemoryWorker(view: MemInfoView) : Runnable {
        private val viewRef = WeakReference(view)

        override fun run() {
            val view = viewRef.get() ?: return
            if (!view.isMonitoring) return

            runCatching { view.buildText() }.getOrNull()?.let { view.postText(it) }
            view.reschedule(this)
        }
    }

    companion object {
        private const val TEXT_SIZE_SP = 12f
        private const val BOTTOM_GAP_DP = 6
        private const val CHIP_PADDING_HORIZONTAL_DP = 12
        private const val CHIP_PADDING_VERTICAL_DP = 4
        private const val CHIP_RADIUS_DP = 100
        private const val CHIP_ALPHA = 38
        private const val REFRESH_INTERVAL_MS = 3000L
        private const val RUNNING_SERVICES_COMMAND = "am start -n com.android.settings/.SubSettings " +
                "--es :settings:show_fragment com.android.settings.applications.RunningServices"
        private val KNOWN_RAM_SIZES = intArrayOf(1, 2, 3, 4, 6, 8, 10, 12, 16, 24, 32, 48, 64)

        @Volatile
        private var workerThread: HandlerThread? = null

        private fun workerLooper(): Looper = workerThread?.looper ?: synchronized(this) {
            workerThread?.looper ?: HandlerThread("PLEMemInfo").also {
                it.start()
                workerThread = it
            }.looper
        }
    }
}
