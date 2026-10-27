package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_SCROLL_FADE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

class DrawerScrollFade(context: Context) : ModPack(context) {

    private var fadeEnabled = false
    private var searchListClass: Class<*>? = null
    private val lists = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val fadeMatrix = Matrix()
    private val location = IntArray(2)
    private val fadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        shader = LinearGradient(0f, 0f, 0f, 1f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val saveUnclipped: Method? by lazy {
        runCatching {
            Canvas::class.java.getDeclaredMethod(
                "saveUnclippedLayer",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()
    }
    private val restoreUnclipped: Method? by lazy {
        runCatching {
            Canvas::class.java.getDeclaredMethod(
                "restoreUnclippedLayer",
                Int::class.javaPrimitiveType,
                Paint::class.java
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            fadeEnabled = getBoolean(DRAWER_SCROLL_FADE, false)
        }

        when (key.firstOrNull()) {
            DRAWER_SCROLL_FADE -> lists.toList().forEach { it.invalidate() }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val listClass = findClass(
            "com.android.launcher3.allapps.AllAppsRecyclerView",
            suppressError = true
        ) ?: return
        searchListClass = findClass("com.android.launcher3.allapps.SearchRecyclerView", suppressError = true)

        val drawClass = generateSequence(listClass.superclass) { it.superclass }
            .takeWhile { it != View::class.java }
            .firstOrNull { owner ->
                owner.declaredMethods.any { it.name == "draw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java)) }
            } ?: return

        listClass
            .hookMethod("onDraw")
            .runBefore { param ->
                val list = param.thisObject as View
                if (!list.isAppList()) return@runBefore
                list.beginFade(param.args[0] as Canvas)
            }

        drawClass
            .hookMethod("draw")
            .parameters(Canvas::class.java)
            .runAfter { param ->
                if (!listClass.isInstance(param.thisObject)) return@runAfter
                (param.thisObject as View).endFade(param.args[0] as Canvas)
            }

        listClass
            .hookMethod("onScrolled")
            .suppressError()
            .runAfter { param ->
                val list = param.thisObject as View
                if (!fadeEnabled || !list.isAppList()) return@runAfter
                val lengths = list.getExtraFieldSilently(FADE_LENGTHS) as? Pair<*, *>
                if (lengths != list.fadeLengths()) list.invalidate()
            }
    }

    private fun View.isAppList(): Boolean {
        lists.add(this)
        return searchListClass?.isInstance(this) != true
    }

    private fun View.beginFade(canvas: Canvas) {
        setExtraField(FADE_STATE, null)
        if (!fadeEnabled || width <= 0 || height <= 0) return

        val lengths = fadeLengths()
        setExtraField(FADE_LENGTHS, lengths)
        val (topLength, bottomLength) = lengths
        if (topLength <= 0 && bottomLength <= 0) return

        val edges = visibleEdges()
        val topEdge = edges[0].coerceIn(0, (height - topLength).coerceAtLeast(0))
        val bottomEdge = edges[1].coerceIn(topEdge + bottomLength, height)

        val state = FadeState(topLength, bottomLength, topEdge, bottomEdge)

        val save = saveUnclipped
        val restore = restoreUnclipped
        if (save != null && restore != null) {
            val saved = runCatching {
                if (topLength > 0) {
                    state.topSave = canvas.saveCount
                    save.invoke(canvas, 0, 0, width, topEdge + topLength)
                }
                if (bottomLength > 0) {
                    state.bottomSave = canvas.saveCount
                    save.invoke(canvas, 0, bottomEdge - bottomLength, width, height)
                }
            }.onFailure { log(this@DrawerScrollFade, it) }.isSuccess
            if (saved) {
                state.unclipped = true
                setExtraField(FADE_STATE, state)
                return
            }
            state.topSave?.let { canvas.restoreToCount(it) } ?: state.bottomSave?.let { canvas.restoreToCount(it) }
            state.topSave = null
            state.bottomSave = null
        }

        state.layerSave = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        setExtraField(FADE_STATE, state)
    }

    private fun View.endFade(canvas: Canvas) {
        val state = getExtraFieldSilently(FADE_STATE) as? FadeState ?: return
        setExtraField(FADE_STATE, null)

        if (state.unclipped) {
            state.bottomSave?.let { save ->
                prepareBottom(state)
                restoreUnclipped?.invoke(canvas, save, fadePaint)
            }
            state.topSave?.let { save ->
                prepareTop(state)
                restoreUnclipped?.invoke(canvas, save, fadePaint)
            }
            return
        }

        if (state.bottomLength > 0) {
            prepareBottom(state)
            canvas.drawRect(0f, (state.bottomEdge - state.bottomLength).toFloat(), width.toFloat(), height.toFloat(), fadePaint)
        }
        if (state.topLength > 0) {
            prepareTop(state)
            canvas.drawRect(0f, 0f, width.toFloat(), (state.topEdge + state.topLength).toFloat(), fadePaint)
        }
        state.layerSave?.let { canvas.restoreToCount(it) }
    }

    private fun prepareTop(state: FadeState) {
        fadeMatrix.setScale(1f, state.topLength.toFloat())
        fadeMatrix.postTranslate(0f, state.topEdge.toFloat())
        fadePaint.shader.setLocalMatrix(fadeMatrix)
    }

    private fun prepareBottom(state: FadeState) {
        fadeMatrix.setScale(1f, state.bottomLength.toFloat())
        fadeMatrix.postRotate(180f)
        fadeMatrix.postTranslate(0f, state.bottomEdge.toFloat())
        fadePaint.shader.setLocalMatrix(fadeMatrix)
    }

    private fun View.fadeLengths(): Pair<Int, Int> {
        val topMax = context.dp(TOP_FADE_DP)
        val bottomMax = context.dp(BOTTOM_FADE_DP)
        val remaining = exactRemaining() ?: estimatedRemaining()
        return remaining[0].coerceIn(0, topMax) to remaining[1].coerceIn(0, bottomMax)
    }

    private fun View.exactRemaining(): IntArray? {
        val group = this as? ViewGroup ?: return null
        val apps = getFieldSilently("mApps")
        val items = (apps.getFieldSilently("mAdapterItems") ?: apps.callMethodSilently("getAdapterItems")) as? List<*>
            ?: return null
        if (items.isEmpty() || group.childCount == 0) return intArrayOf(0, 0)

        var first: View? = null
        var last: View? = null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (first == null || child.top < first.top) first = child
            if (last == null || child.bottom >= last.bottom) last = child
        }
        val firstPosition = callMethodSilently("getChildAdapterPosition", first) as? Int ?: return null
        val lastPosition = callMethodSilently("getChildAdapterPosition", last) as? Int ?: return null

        val top = if (firstPosition == 0) paddingTop - first!!.top else Int.MAX_VALUE
        val bottom = if (lastPosition == items.size - 1) last!!.bottom - (height - paddingBottom) else Int.MAX_VALUE
        return intArrayOf(top, bottom)
    }

    private fun View.estimatedRemaining(): IntArray {
        val offset = callMethodSilently("computeVerticalScrollOffset") as? Int ?: 0
        val range = callMethodSilently("computeVerticalScrollRange") as? Int ?: 0
        val extent = callMethodSilently("computeVerticalScrollExtent") as? Int ?: 0
        return intArrayOf(
            if (canScrollVertically(-1)) offset else 0,
            if (canScrollVertically(1)) range - extent - offset else 0
        )
    }

    private fun View.visibleEdges(): IntArray {
        val clip = clipBounds
        val edges = intArrayOf(clip?.top ?: 0, clip?.bottom ?: height)
        AppDrawerTabs.shownTabBar()?.let { bar ->
            val barTop = bar.windowY() - windowY()
            val barBottom = barTop + bar.height
            if (barBottom <= 0 || barTop >= height) return@let
            if (barTop + barBottom < height) edges[0] = maxOf(edges[0], barBottom)
            else edges[1] = minOf(edges[1], barTop)
        }
        return edges
    }

    private fun View.windowY(): Int {
        getLocationInWindow(location)
        return location[1]
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class FadeState(
        val topLength: Int,
        val bottomLength: Int,
        val topEdge: Int,
        val bottomEdge: Int
    ) {
        var unclipped = false
        var topSave: Int? = null
        var bottomSave: Int? = null
        var layerSave: Int? = null
    }

    companion object {
        private const val FADE_STATE = "pleScrollFadeState"
        private const val FADE_LENGTHS = "pleScrollFadeLengths"
        private const val TOP_FADE_DP = 24
        private const val BOTTOM_FADE_DP = 32
    }
}
