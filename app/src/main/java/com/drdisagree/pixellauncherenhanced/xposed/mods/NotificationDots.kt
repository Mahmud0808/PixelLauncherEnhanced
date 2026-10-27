package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NOTIFICATION_DOT_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NOTIFICATION_DOT_BLOCK_LIST_ENABLED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NOTIFICATION_DOT_COUNT
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlin.math.max
import kotlin.math.min

class NotificationDots(context: Context) : ModPack(context) {

    private var showCount = false
    private var blockListEnabled = false
    private var blockList: Set<String> = emptySet()
    private val pendingCount = ThreadLocal<Int?>()

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            showCount = getBoolean(NOTIFICATION_DOT_COUNT, false)
            blockListEnabled = getBoolean(NOTIFICATION_DOT_BLOCK_LIST_ENABLED, false)
            blockList = getStringSet(NOTIFICATION_DOT_BLOCK_LIST, emptySet()).orEmpty().toSet()
        }

        when (key.firstOrNull()) {
            NOTIFICATION_DOT_COUNT,
            NOTIFICATION_DOT_BLOCK_LIST_ENABLED,
            NOTIFICATION_DOT_BLOCK_LIST -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        findClass("com.android.launcher3.BubbleTextView")
            .hookMethod("drawDotIfNecessary")
            .suppressError()
            .runBefore { param ->
                val view = param.thisObject as View
                if (view.isDotBlocked()) {
                    param.result = null
                    return@runBefore
                }
                if (showCount) pendingCount.set(view.dotCount())
            }
            .runAfter { pendingCount.remove() }

        findClass("com.android.launcher3.folder.FolderIcon")
            .hookMethod("drawDot")
            .suppressError()
            .runBefore { param ->
                if (!showCount && !blockListEnabled) return@runBefore

                val icons = (param.thisObject.getFieldSilently("mFolder")
                    .callMethodSilently("getIconsInReadingOrder") as? List<*>)
                    .orEmpty()
                    .filterIsInstance<View>()
                    .filter { it.dotCount() > 0 }

                val visibleCount = if (icons.isEmpty()) {
                    param.thisObject.getFieldSilently("mDotInfo").notificationCount()
                } else {
                    icons.filterNot { it.isDotBlocked() }.sumOf { it.dotCount() }
                }

                if (visibleCount <= 0 && icons.isNotEmpty()) {
                    param.result = null
                    return@runBefore
                }
                if (showCount) pendingCount.set(visibleCount)
            }
            .runAfter { pendingCount.remove() }

        findClass("com.android.launcher3.icons.DotRenderer")
            .hookMethod("draw")
            .suppressError()
            .runBefore { param ->
                val canvas = param.args.getOrNull(0) as? Canvas ?: return@runBefore
                val params = param.args.getOrNull(1) ?: return@runBefore
                val count = pendingCount.get()?.takeIf { it > 0 } ?: return@runBefore
                if (drawCount(canvas, param.thisObject, params, count)) param.result = null
            }
    }

    private fun drawCount(canvas: Canvas, renderer: Any, params: Any, count: Int): Boolean {
        val iconBounds = params.getFieldSilently("iconBounds") as? Rect ?: return false
        val scale = params.getFieldSilently("scale") as? Float ?: 1f
        if (scale <= 0f) return true

        val leftAlign = params.getFieldSilently("leftAlign") as? Boolean == true
        val position = dotPosition(renderer, params, leftAlign)
        val baseRadius = renderer.getFieldSilently("mCircleRadius") as? Float
            ?: (iconBounds.width() * 0.1f)
        val color = params.getAnyFieldSilently("mDotColor", "dotColor") as? Int ?: DEFAULT_DOT_COLOR
        val text = if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString()

        textPaint.textSize = baseRadius * 1.45f
        val radius = max(baseRadius * 1.3f, textPaint.measureText(text) / 2f + baseRadius * 0.45f)
        val clip = canvas.clipBounds

        var centerX = iconBounds.left + iconBounds.width() * position.x
        var centerY = iconBounds.top + iconBounds.height() * position.y
        centerX = if (leftAlign) max(centerX, clip.left + radius) else min(centerX, clip.right - radius)
        centerY = max(centerY, clip.top + radius)

        circlePaint.color = color
        textPaint.color = contrastingTextColor(color)

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, radius, circlePaint)
        canvas.drawText(text, 0f, -(textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        canvas.restore()

        return true
    }

    private fun dotPosition(renderer: Any, params: Any, leftAlign: Boolean): PointF {
        val legacy = renderer.getFieldSilently(
            if (leftAlign) "mLeftDotPosition" else "mRightDotPosition"
        ) as? FloatArray
        if (legacy != null && legacy.size >= 2) return PointF(legacy[0], legacy[1])

        val shapePosition = params.getFieldSilently("shapeInfo").getFieldSilently(
            if (leftAlign) "leftCornerPosition" else "rightCornerPosition"
        ) as? PointF
        if (shapePosition != null) return shapePosition

        return if (leftAlign) PointF(0.15f, 0.15f) else PointF(0.85f, 0.15f)
    }

    private fun contrastingTextColor(background: Int): Int {
        return if (ColorUtils.calculateLuminance(background or 0xFF000000.toInt()) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun View.isDotBlocked(): Boolean {
        if (!blockListEnabled || blockList.isEmpty()) return false

        val info = tag ?: return false
        val packageName = info.callMethodSilently("getTargetPackage") as? String
            ?: (info.callMethodSilently("getTargetComponent") as? ComponentName)?.packageName
            ?: return false

        return packageName in blockList
    }

    private fun View.dotCount(): Int = getFieldSilently("mDotInfo").notificationCount()

    private fun Any?.notificationCount(): Int {
        if (this == null) return 0
        val total = callMethodSilently("getNotificationCount") as? Int
            ?: getAnyFieldSilently("mTotalCount", "totalCount") as? Int
            ?: 0
        val keys = (getAnyFieldSilently("mNotificationKeys", "notificationKeys") as? List<*>
            ?: declaredListField())?.size ?: 0
        return maxOf(total, keys, 1)
    }

    private fun Any.declaredListField(): List<*>? {
        var type: Class<*>? = javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { List::class.java.isAssignableFrom(it.type) }?.let { field ->
                return runCatching { field.isAccessible = true; field.get(this) as? List<*> }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    companion object {
        private const val DEFAULT_DOT_COLOR = 0xFFE53935.toInt()
        private const val MAX_COUNT = 99
    }
}
