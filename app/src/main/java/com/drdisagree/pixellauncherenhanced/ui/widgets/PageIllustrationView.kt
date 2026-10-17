package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DISABLE_DOCK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_BLUR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_RADIUS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BLUR_TINT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BLUR_STYLE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HOME_PAGE_TRANSITION
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_ICON_SIZE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_BAR_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.Constants.WIDGET_BACKGROUND
import com.drdisagree.pixellauncherenhanced.data.common.PanelBackground
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class PageIllustrationView(context: Context, private val scene: Scene) : View(context) {

    enum class Scene(val duration: Long) {
        HOME(4800), DOCK(8000), FOLDERS(4200), WIDGETS(4200), DRAWER(4200), ICONS(4800), GESTURES(5200), RECENTS(4800)
    }

    private enum class Crop { NONE, TOP, BOTTOM, BOTH }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val rect = RectF()
    private val inner = RectF()
    private val device = RectF()
    private val screen = RectF()
    private val path = Path()
    private val camera = Camera()
    private val matrix = Matrix()
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val standard = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private val primary = color(androidx.appcompat.R.attr.colorPrimary)
    private val primaryContainer = color(MaterialR.attr.colorPrimaryContainer)
    private val onPrimaryContainer = color(MaterialR.attr.colorOnPrimaryContainer)
    private val secondary = color(MaterialR.attr.colorSecondary)
    private val secondaryContainer = color(MaterialR.attr.colorSecondaryContainer)
    private val onSecondaryContainer = color(MaterialR.attr.colorOnSecondaryContainer)
    private val tertiary = color(MaterialR.attr.colorTertiary)
    private val tertiaryContainer = color(MaterialR.attr.colorTertiaryContainer)
    private val onTertiaryContainer = color(MaterialR.attr.colorOnTertiaryContainer)
    private val surface = color(MaterialR.attr.colorSurface)
    private val surfaceHigh = color(MaterialR.attr.colorSurfaceContainerHigh)
    private val surfaceHighest = color(MaterialR.attr.colorSurfaceContainerHighest)
    private val surfaceLow = color(MaterialR.attr.colorSurfaceContainerLow)
    private val onSurface = color(MaterialR.attr.colorOnSurface)
    private val onSurfaceVariant = color(MaterialR.attr.colorOnSurfaceVariant)
    private val outlineVariant = color(MaterialR.attr.colorOutlineVariant)

    private val iconBackgrounds = intArrayOf(
        primary, tertiaryContainer, secondary, primaryContainer, tertiary, secondaryContainer
    )
    private val iconForegrounds = intArrayOf(
        color(MaterialR.attr.colorOnPrimary), onTertiaryContainer, color(MaterialR.attr.colorOnSecondary),
        onPrimaryContainer, color(MaterialR.attr.colorOnTertiary), onSecondaryContainer
    )

    private var wallpaperShader: Shader? = null
    private var glowShader: Shader? = null
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private var searchShown = 1f
    private var dockShown = 1f
    private var pillShown = 1f
    private var navSpaceShown = 1f
    private var chromeAnimator: ValueAnimator? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in CHROME_KEYS) animateChrome() else morphConfig()
    }

    private data class Config(
        val columns: Int,
        val rows: Int,
        val transition: String,
        val dockMode: String,
        val dockAttached: Boolean,
        val searchMode: String,
        val folderColumns: Int,
        val folderRows: Int,
        val folderMode: String,
        val widgetMode: String,
        val dockGlass: Glass,
        val searchGlass: Glass,
        val widgetGlass: Glass,
        val folderGlass: Glass,
        val drawerColumns: Int,
        val iconScale: Float
    )

    private data class Glass(val blur: Int, val tint: Int)

    private val backdropNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode("illustrationBackdrop") else null
    private var backdrop: (Canvas) -> Unit = { drawWallpaper(it) }
    private var recordingBackdrop = false

    private var config = readConfig()
    private var active = config
    private var previous: Config? = null
    private var morph = 1f
    private var morphAnimator: ValueAnimator? = null
    private var secondPass = false
    private var deferFade = false
    private var pendingCrop = Crop.NONE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        config = readConfig()
        active = config
        previous = null
        searchShown = if (searchHidden()) 0f else 1f
        dockShown = if (dockHidden()) 0f else 1f
        pillShown = if (RPrefs.getBoolean(HIDE_GESTURE_PILL, false)) 0f else 1f
        navSpaceShown = if (navSpaceHidden()) 0f else 1f
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        RPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        stopAnimation()
        chromeAnimator?.cancel()
        morphAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) startAnimation() else stopAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glowShader = RadialGradient(
            w / 2f, h / 2f, min(w, h) * 0.5f,
            intArrayOf(withAlpha(primary, 0.22f), withAlpha(primary, 0.08f), withAlpha(primary, 0f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        wallpaperShader = null
    }

    private fun startAnimation() {
        if (animator != null || !isAttachedToWindow) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            progress = STATIC_PROGRESS[scene.ordinal]
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = scene.duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun morphConfig() {
        val next = readConfig()
        if (next == config) return
        previous = config
        config = next
        morphAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            previous = null
            invalidate()
            return
        }
        morph = 0f
        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CHROME_DURATION_MS
            interpolator = standard
            addUpdateListener {
                morph = it.animatedValue as Float
                if (morph >= 1f) previous = null
                invalidate()
            }
            start()
        }
    }

    private fun readConfig(): Config {
        val columns = RPrefs.getSliderInt(DESKTOP_GRID_COLUMNS, 0).takeIf { it > 0 }?.coerceAtMost(8) ?: DEFAULT_COLUMNS
        return Config(
            columns = columns,
            rows = RPrefs.getSliderInt(DESKTOP_GRID_ROWS, 0).takeIf { it > 0 }?.coerceAtMost(9) ?: DEFAULT_ROWS,
            transition = RPrefs.getString(HOME_PAGE_TRANSITION, "none") ?: "none",
            dockMode = PanelBackground.supported(RPrefs.getString(DOCK_BACKGROUND, PanelBackground.OFF)),
            dockAttached = RPrefs.getString(DOCK_BLUR_STYLE, DOCK_FLOATING) == DOCK_ATTACHED,
            searchMode = PanelBackground.supported(RPrefs.getString(SEARCH_BAR_BACKGROUND, PanelBackground.OFF)),
            folderColumns = RPrefs.getSliderInt(FOLDER_GRID_COLUMNS, 0).takeIf { it > 0 }?.coerceAtMost(6) ?: DEFAULT_FOLDER_COLUMNS,
            folderRows = RPrefs.getSliderInt(FOLDER_GRID_ROWS, 0).takeIf { it > 0 }?.coerceAtMost(6) ?: DEFAULT_FOLDER_ROWS,
            folderMode = PanelBackground.supported(RPrefs.getString(FOLDER_BACKGROUND, PanelBackground.OFF)),
            widgetMode = PanelBackground.supported(RPrefs.getString(WIDGET_BACKGROUND, PanelBackground.OFF)),
            dockGlass = Glass(RPrefs.getSliderInt(DOCK_BLUR_RADIUS, DEFAULT_BLUR), RPrefs.getSliderInt(DOCK_BLUR_TINT, DEFAULT_TINT)),
            searchGlass = Glass(RPrefs.getSliderInt(SEARCH_BAR_BLUR_RADIUS, DEFAULT_BLUR), RPrefs.getSliderInt(SEARCH_BAR_BLUR_TINT, DEFAULT_TINT)),
            widgetGlass = Glass(RPrefs.getSliderInt(WIDGET_BLUR_RADIUS, DEFAULT_BLUR), RPrefs.getSliderInt(WIDGET_BLUR_TINT, DEFAULT_TINT)),
            folderGlass = Glass(RPrefs.getSliderInt(FOLDER_BACKGROUND_BLUR, DEFAULT_BLUR), RPrefs.getSliderInt(FOLDER_BACKGROUND_OPACITY, DEFAULT_TINT)),
            drawerColumns = RPrefs.getSliderInt(APP_DRAWER_GRID_COLUMNS, 0).takeIf { it > 0 }?.coerceAtMost(8) ?: columns,
            iconScale = (RPrefs.getSliderInt(LAUNCHER_ICON_SIZE, 100) / 100f).coerceIn(0.5f, 1.5f)
        )
    }

    private fun animateChrome() {
        val searchTarget = if (searchHidden()) 0f else 1f
        val dockTarget = if (dockHidden()) 0f else 1f
        val pillTarget = if (RPrefs.getBoolean(HIDE_GESTURE_PILL, false)) 0f else 1f
        val navTarget = if (navSpaceHidden()) 0f else 1f
        chromeAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            searchShown = searchTarget
            dockShown = dockTarget
            pillShown = pillTarget
            navSpaceShown = navTarget
            invalidate()
            return
        }
        val searchStart = searchShown
        val dockStart = dockShown
        val pillStart = pillShown
        val navStart = navSpaceShown
        chromeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CHROME_DURATION_MS
            interpolator = emphasized
            addUpdateListener {
                val t = it.animatedValue as Float
                searchShown = lerp(searchStart, searchTarget, t)
                dockShown = lerp(dockStart, dockTarget, t)
                pillShown = lerp(pillStart, pillTarget, t)
                navSpaceShown = lerp(navStart, navTarget, t)
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        shaderPaint.shader = glowShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)

        val from = previous
        if (from == null || scene == Scene.ICONS || scene == Scene.RECENTS) {
            active = config
            drawScene(canvas)
            return
        }
        val outer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        deferFade = true
        pendingCrop = Crop.NONE
        active = from
        drawScene(canvas)
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (morph * 255).toInt())
        secondPass = true
        active = config
        drawScene(canvas)
        secondPass = false
        canvas.restoreToCount(layer)
        deferFade = false
        applyFade(canvas, pendingCrop)
        canvas.restoreToCount(outer)
    }

    private fun drawScene(canvas: Canvas) {
        backdrop = { drawWallpaper(it) }
        when (scene) {
            Scene.HOME -> drawHome(canvas)
            Scene.DOCK -> drawDock(canvas)
            Scene.FOLDERS -> drawFolders(canvas)
            Scene.WIDGETS -> drawWidgets(canvas)
            Scene.DRAWER -> drawDrawer(canvas)
            Scene.ICONS -> drawIcons(canvas)
            Scene.GESTURES -> drawGestures(canvas)
            Scene.RECENTS -> drawRecents(canvas)
        }
    }

    private fun drawHome(canvas: Canvas) {
        val columns = homeColumns()
        val rows = homeRows()
        val transition = active.transition
        val swipe = phase(0.3f, 0.52f, 0.78f, 1f)

        fullDevice()
        drawDevice(canvas, Crop.NONE) {
            drawWallpaper(this)
            drawStatusBar(this)
            withSave {
                clipRect(screen.left, screen.top + screen.height() * 0.06f, screen.right, gridBottom())
                drawPage(this, -swipe, transition, columns, rows, 0)
                drawPage(this, 1f - swipe, transition, columns, rows, 1)
            }
            drawPageIndicator(this, swipe)
            drawDockRow(this, columns, dockMode())
            drawSearchBar(this, searchMode())
            drawNavHandle(this)
        }
    }

    private fun drawPage(canvas: Canvas, offset: Float, transition: String, columns: Int, rows: Int, seed: Int) {
        if (abs(offset) >= 1f) return
        val w = screen.width()
        canvas.withSave {
            var alpha = 1f
            when (transition) {
                "cube" -> {
                    translate(offset * w, 0f)
                    camera.save()
                    camera.setLocation(0f, 0f, -10f * density)
                    camera.rotateY(-offset * 90f)
                    camera.getMatrix(matrix)
                    camera.restore()
                    val pivotX = if (offset > 0f) screen.left else screen.right
                    matrix.preTranslate(-pivotX, -screen.centerY())
                    matrix.postTranslate(pivotX, screen.centerY())
                    concat(matrix)
                }

                "stack" -> if (offset < 0f) {
                    val s = 1f + offset * 0.2f
                    scale(s, s, screen.centerX(), screen.centerY())
                    alpha = 1f + offset
                } else {
                    translate(offset * w, 0f)
                }

                "fade" -> {
                    translate(offset * w, 0f)
                    alpha = 1f - abs(offset)
                }

                "zoom" -> {
                    translate(offset * w, 0f)
                    val s = 1f - abs(offset) * 0.25f
                    scale(s, s, screen.centerX(), screen.centerY())
                }

                else -> translate(offset * w, 0f)
            }
            if (seed == 0) drawGlance(this, alpha)
            drawGrid(this, columns, rows, alpha) { r, c ->
                if (seed == 0) r >= rows - 2 || (r == rows - 3 && c == 0) else (r * 7 + c * 3) % 5 != 0 && r < rows - 1
            }
        }
    }

    private fun drawDock(canvas: Canvas) {
        val columns = homeColumns()
        deviceBleeding(Crop.TOP, visibleFrom = 0.42f, visibleTo = 1f)
        drawDevice(canvas, Crop.TOP) {
            drawWallpaper(this)
            drawGrid(this, columns, homeRows(), 1f) { r, c -> r >= homeRows() - 2 || (r + c) % 4 == 0 }
            drawPageIndicator(this, 0f)
            drawDockRow(this, columns, dockMode())
            drawSearchBar(this, searchMode())
            drawNavHandle(this)
        }
    }

    private fun drawFolders(canvas: Canvas) {
        val columns = active.folderColumns
        val rows = active.folderRows
        val mode = active.folderMode
        val open = phase(0.18f, 0.4f, 0.78f, 0.98f)
        val homeColumns = homeColumns()
        val homeRows = homeRows()
        val folderRow = homeRows - 1
        val folderColumn = 1

        deviceBleeding(Crop.BOTH, visibleFrom = 0.24f, visibleTo = 0.86f)
        drawDevice(canvas, Crop.BOTH) {
            drawWallpaper(this)
            drawGrid(this, homeColumns, homeRows, 1f, folder = folderRow to folderColumn, folderAlpha = 1f - open) { r, _ -> r >= homeRows - 3 }
            drawDockRow(this, homeColumns, dockMode())

            fill.color = withAlpha(Color.BLACK, 0.3f * open)
            drawRect(screen, fill)

            backdrop = { c ->
                drawWallpaper(c)
                drawGrid(c, homeColumns, homeRows, 1f, folder = folderRow to folderColumn, folderAlpha = 1f - open) { r, _ -> r >= homeRows - 3 }
                drawDockRow(c, homeColumns, dockMode())
                fill.color = withAlpha(Color.BLACK, 0.3f * open)
                c.drawRect(screen, fill)
            }

            if (open > 0.001f) {
                val start = cellCenter(homeColumns, homeRows, folderRow, folderColumn)
                val startSize = iconSize(homeColumns, homeRows)
                val cell = min(screen.width() * 0.74f / columns, screen.height() * 0.42f / rows)
                val panelW = cell * columns + cell * 0.5f
                val panelH = cell * rows + cell * 1.1f
                val finalX = screen.centerX()
                val finalY = screen.top + screen.height() * 0.52f
                val cx = lerp(start.first, finalX, open)
                val cy = lerp(start.second, finalY, open)
                val w = lerp(startSize, panelW, open)
                val h = lerp(startSize, panelH, open)
                rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                drawPanel(this, rect, lerp(startSize * 0.5f, cell * 0.5f, open), mode, native = true, glass = active.folderGlass)

                val contentAlpha = ((open - 0.35f) / 0.65f).coerceIn(0f, 1f)
                if (contentAlpha > 0f) {
                    withSave {
                        path.reset()
                        path.addRoundRect(rect, cell * 0.5f * open, cell * 0.5f * open, Path.Direction.CW)
                        clipPath(path)
                        translate(cx, cy)
                        scale(w / panelW, h / panelH)
                        translate(-finalX, -finalY)
                        val left = finalX - cell * columns / 2
                        val top = finalY - panelH / 2 + cell * 0.3f
                        for (i in 0 until columns * rows) {
                            drawAppIcon(this, left + cell * (i % columns + 0.5f), top + cell * (i / columns + 0.5f), cell * 0.66f, i, contentAlpha)
                        }
                        fill.color = withAlpha(if (mode == PanelBackground.OFF) onSurfaceVariant else Color.WHITE, 0.6f * contentAlpha)
                        val labelY = finalY + panelH / 2 - cell * 0.42f
                        inner.set(finalX - cell * 0.6f, labelY - cell * 0.05f, finalX + cell * 0.6f, labelY + cell * 0.05f)
                        drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
                    }
                }
            }
        }
    }

    private fun drawWidgets(canvas: Canvas) {
        val mode = active.widgetMode
        val columns = homeColumns()
        val rows = homeRows()
        val grow = phase(0.14f, 0.42f, 0.64f, 0.92f)
        val resizing = progress in 0.06f..0.96f
        val frameAlpha = when {
            progress < 0.06f -> 0f
            progress < 0.12f -> (progress - 0.06f) / 0.06f
            progress > 0.94f -> ((0.98f - progress) / 0.04f).coerceIn(0f, 1f)
            else -> 1f
        }

        deviceBleeding(Crop.BOTTOM, visibleFrom = 0f, visibleTo = 0.6f)
        drawDevice(canvas, Crop.BOTTOM) {
            drawWallpaper(this)
            drawStatusBar(this)
            drawGrid(this, columns, rows, 1f) { r, c -> r >= 2 && (r + c) % 3 != 1 }

            val pad = gridPad()
            val cellW = (screen.width() - pad * 2) / columns
            val top = gridTop()
            val cellH = (gridBottom() - top) / rows

            if (resizing) {
                fill.color = withAlpha(Color.WHITE, 0.6f * frameAlpha)
                for (r in 0..2) for (c in 0..columns) {
                    drawCircle(screen.left + pad + cellW * c, top + cellH * r, 1.2f * density, fill)
                }
            }

            val spans = lerp(2f, columns.toFloat(), grow)
            val inset = cellW * 0.08f
            rect.set(screen.left + pad + inset, top + inset, screen.left + pad + cellW * spans - inset, top + cellH * 2 - inset)
            val radius = rect.height() * 0.18f
            drawPanel(this, rect, radius, mode, native = true, glass = active.widgetGlass)

            val textColor = if (mode == PanelBackground.OFF) onSurface else Color.WHITE
            fill.color = withAlpha(textColor, 0.9f)
            inner.set(rect.left + rect.height() * 0.18f, rect.top + rect.height() * 0.22f, 0f, 0f)
            inner.right = inner.left + min(rect.width() * 0.5f, rect.height() * 1.05f)
            inner.bottom = inner.top + rect.height() * 0.2f
            drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
            fill.color = withAlpha(textColor, 0.55f)
            inner.top = inner.bottom + rect.height() * 0.12f
            inner.bottom = inner.top + rect.height() * 0.09f
            inner.right = inner.left + min(rect.width() * 0.34f, rect.height() * 0.7f)
            drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)

            val sunR = rect.height() * 0.16f
            val sunX = rect.right - rect.height() * 0.36f
            fill.color = tertiary
            drawCircle(sunX, rect.centerY(), sunR, fill)
            fill.color = withAlpha(Color.WHITE, 0.35f)
            drawCircle(sunX - sunR * 0.3f, rect.centerY() - sunR * 0.3f, sunR * 0.45f, fill)

            if (frameAlpha > 0f) {
                stroke.color = withAlpha(Color.WHITE, frameAlpha)
                stroke.strokeWidth = 1.6f * density
                drawRoundRect(rect, radius, radius, stroke)
                val handle = 3.6f * density
                for ((hx, hy) in listOf(rect.left to rect.centerY(), rect.right to rect.centerY(), rect.centerX() to rect.top, rect.centerX() to rect.bottom)) {
                    fill.color = withAlpha(primary, frameAlpha)
                    drawCircle(hx, hy, handle + 1.2f * density, fill)
                    fill.color = withAlpha(Color.WHITE, frameAlpha)
                    drawCircle(hx, hy, handle * 0.55f, fill)
                }
            }
        }
    }

    private fun drawDrawer(canvas: Canvas) {
        val homeColumns = homeColumns()
        val columns = active.drawerColumns
        val open = phase(0.2f, 0.44f, 0.78f, 0.98f)

        fullDevice()
        drawDevice(canvas, Crop.NONE) {
            drawWallpaper(this)
            drawStatusBar(this)
            drawGlance(this, 1f - open)
            drawGrid(this, homeColumns, homeRows(), 1f - open) { r, _ -> r >= homeRows() - 2 }
            drawDockRow(this, homeColumns, dockMode())
            drawSearchBar(this, searchMode())

            val top = lerp(screen.bottom, screen.top + screen.height() * 0.07f, open)
            val sheetRadius = screen.width() * 0.12f
            rect.set(screen.left, top, screen.right, screen.bottom + sheetRadius)
            fill.color = surfaceLow
            drawRoundRect(rect, sheetRadius, sheetRadius, fill)

            val pad = screen.width() * 0.07f
            fill.color = surfaceHighest
            inner.set(screen.left + pad, top + pad * 1.2f, screen.right - pad, top + pad * 1.2f + screen.height() * 0.06f)
            drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
            fill.color = withAlpha(onSurfaceVariant, 0.6f)
            drawCircle(inner.left + inner.height() * 0.6f, inner.centerY(), inner.height() * 0.18f, fill)

            val cell = (screen.width() - pad * 2) / columns
            val size = cell * 0.64f
            val gridTop = inner.bottom + pad * 0.5f
            var i = 0
            var r = 0
            while (gridTop + cell * r < screen.bottom) {
                for (c in 0 until columns) {
                    drawAppIcon(this, screen.left + pad + cell * (c + 0.5f), gridTop + cell * (r + 0.5f) - cell * 0.08f, size, i++, 1f)
                    fill.color = withAlpha(onSurfaceVariant, 0.35f)
                    val lw = size * 0.7f
                    val ly = gridTop + cell * (r + 0.5f) + size * 0.5f
                    inner.set(screen.left + pad + cell * (c + 0.5f) - lw / 2, ly, screen.left + pad + cell * (c + 0.5f) + lw / 2, ly + size * 0.1f)
                    drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
                }
                r++
            }
            drawNavHandle(this)
        }
    }

    private fun drawIcons(canvas: Canvas) {
        val scale = previous?.let { lerp(it.iconScale, config.iconScale, morph) } ?: config.iconScale
        val count = 4
        val slot = min(width / (count + 1f), height * 0.42f)
        val size = slot * 0.7f * scale
        val start = (width - slot * count) / 2f

        for (i in 0 until count) {
            val local = ((progress - i * 0.05f) % 1f + 1f) % 1f
            val themed = when {
                local < 0.36f -> 0f
                local < 0.5f -> emphasized.getInterpolation((local - 0.36f) / 0.14f)
                local < 0.86f -> 1f
                else -> 1f - emphasized.getInterpolation((local - 0.86f) / 0.14f)
            }
            val lift = sin(themed * Math.PI.toFloat()) * 6f * density
            val cx = start + slot * (i + 0.5f)
            val cy = height * 0.46f - lift

            shaderPaint.shader = RadialGradient(cx, height * 0.46f + size * 0.58f, size * 0.5f, withAlpha(Color.BLACK, 0.16f), withAlpha(Color.BLACK, 0f), Shader.TileMode.CLAMP)
            canvas.save()
            canvas.scale(1f, 0.25f, cx, height * 0.46f + size * 0.58f)
            canvas.drawCircle(cx, height * 0.46f + size * 0.58f, size * 0.5f, shaderPaint)
            canvas.restore()

            val pair = pairIndex(i)
            val shape = ColorUtils.blendARGB(iconBackgrounds[pair], primaryContainer, themed)
            val glyph = ColorUtils.blendARGB(iconForegrounds[pair], onPrimaryContainer, themed)
            drawIconShape(canvas, cx, cy, size, shape, glyph, i, 1f)

            fill.color = withAlpha(onSurfaceVariant, 0.5f)
            inner.set(cx - slot * 0.24f, height * 0.46f + size * 0.82f, cx + slot * 0.24f, height * 0.46f + size * 0.82f + 4f * density)
            canvas.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
        }
    }

    private fun drawGestures(canvas: Canvas) {
        val pinch = phase(0.36f, 0.5f, 0.6f, 0.7f)
        val columns = homeColumns()
        val rows = homeRows()

        fullDevice()
        drawDevice(canvas, Crop.NONE) {
            drawWallpaper(this)
            drawStatusBar(this)
            withSave {
                val s = 1f - pinch * 0.1f
                scale(s, s, screen.centerX(), screen.centerY())
                drawGlance(this, 1f)
                drawGrid(this, columns, rows, 1f) { r, c -> r >= rows - 2 || (r == 1 && c < 2) }
                drawPageIndicator(this, 0f)
                drawDockRow(this, columns, dockMode())
            }

            val cx = screen.centerX()
            val cy = screen.top + screen.height() * 0.4f
            when {
                progress < 0.32f -> for (tap in 0..1) {
                    val t = (progress - 0.05f - tap * 0.09f) / 0.14f
                    if (t in 0f..1f) drawTouch(this, cx, cy, t)
                }

                progress in 0.33f..0.74f -> {
                    val spread = lerp(screen.width() * 0.3f, screen.width() * 0.08f, pinch)
                    val fade = when {
                        progress < 0.37f -> (progress - 0.33f) / 0.04f
                        progress > 0.7f -> (0.74f - progress) / 0.04f
                        else -> 1f
                    }.coerceIn(0f, 1f)
                    drawFinger(this, cx - spread, cy + spread * 0.7f, fade)
                    drawFinger(this, cx + spread, cy - spread * 0.7f, fade)
                }

                progress > 0.78f -> {
                    val t = ((progress - 0.78f) / 0.18f).coerceIn(0f, 1f)
                    val y = lerp(screen.bottom - 8f * density, screen.top + screen.height() * 0.55f, emphasized.getInterpolation(t))
                    drawFinger(this, cx, y, if (t < 0.8f) 1f else (1f - t) / 0.2f)
                }
            }
            drawNavHandle(this)
        }
    }

    private fun drawRecents(canvas: Canvas) {
        val cardH = height * 0.78f
        val cardW = cardH * 0.48f
        val spacing = cardW * 1.16f
        val cy = height * 0.5f
        val shift = phase(0.12f, 0.34f, 0.48f, 0.7f)
        val dismiss = when {
            progress < 0.74f -> 0f
            progress < 0.86f -> standard.getInterpolation((progress - 0.74f) / 0.12f)
            else -> 1f
        }
        val returnIn = if (progress > 0.88f) emphasized.getInterpolation((progress - 0.88f) / 0.12f) else 0f

        for (i in -2..2) {
            val offset = i + shift
            val cx = width / 2f + offset * spacing
            if (cx + cardW < 0 || cx - cardW > width) continue
            val s = 1f - min(abs(offset), 1f) * 0.06f
            var dy = 0f
            var alpha = 1f
            if (i == 0) {
                if (progress < 0.88f) {
                    dy = -dismiss * height * 0.7f
                    alpha = 1f - dismiss
                } else {
                    alpha = returnIn
                    dy = (1f - returnIn) * height * 0.08f
                }
            }
            rect.set(cx - cardW / 2 * s, cy - cardH / 2 * s + dy, cx + cardW / 2 * s, cy + cardH / 2 * s + dy)
            drawTaskCard(canvas, RectF(rect), i + 2, alpha)
        }
    }

    private fun drawTaskCard(canvas: Canvas, bounds: RectF, index: Int, alpha: Float) {
        if (alpha <= 0f) return
        val radius = bounds.width() * 0.16f
        drawShadow(canvas, bounds, radius, alpha)
        canvas.withSave {
            path.reset()
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            clipPath(path)
            fill.color = withAlpha(surface, alpha)
            drawRect(bounds, fill)
            val accent = intArrayOf(primaryContainer, tertiaryContainer, secondaryContainer)[index % 3]
            fill.color = withAlpha(accent, alpha)
            inner.set(bounds.left, bounds.top, bounds.right, bounds.top + bounds.height() * 0.36f)
            drawRect(inner, fill)
            val pad = bounds.width() * 0.12f
            fill.color = withAlpha(surfaceHighest, alpha)
            for (line in 0..3) {
                val top = bounds.top + bounds.height() * (0.44f + line * 0.12f)
                inner.set(bounds.left + pad, top, bounds.right - pad - (line % 2) * bounds.width() * 0.2f, top + bounds.height() * 0.07f)
                drawRoundRect(inner, inner.height() * 0.4f, inner.height() * 0.4f, fill)
            }
        }
        val chipH = bounds.width() * 0.2f
        val chipPad = bounds.width() * 0.06f
        rect.set(bounds.left + chipPad, bounds.top + chipPad, bounds.left + bounds.width() * 0.66f, bounds.top + chipPad + chipH)
        fill.color = withAlpha(surfaceHigh, alpha)
        canvas.drawRoundRect(rect, chipH / 2, chipH / 2, fill)
        drawAppIcon(canvas, rect.left + chipH / 2, rect.centerY(), chipH * 0.78f, index + 3, alpha)
        fill.color = withAlpha(onSurfaceVariant, 0.7f * alpha)
        inner.set(rect.left + chipH * 1.05f, rect.centerY() - chipH * 0.09f, rect.right - chipH * 0.45f, rect.centerY() + chipH * 0.09f)
        canvas.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
    }

    private inline fun drawDevice(canvas: Canvas, crop: Crop, content: Canvas.() -> Unit) {
        pendingCrop = crop
        val faded = crop != Crop.NONE && !deferFade
        val layer = if (faded) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) else -1

        val radius = device.width() * DEVICE_RADIUS
        drawShadow(canvas, device, radius, 1f)
        fill.color = BEZEL
        canvas.drawRoundRect(device, radius, radius, fill)
        stroke.color = withAlpha(outlineVariant, 0.8f)
        stroke.strokeWidth = 1f * density
        canvas.drawRoundRect(device, radius, radius, stroke)

        val bezel = device.width() * BEZEL_RATIO
        screen.set(device.left + bezel, device.top + bezel, device.right - bezel, device.bottom - bezel)
        canvas.withSave {
            path.reset()
            path.addRoundRect(screen, radius - bezel, radius - bezel, Path.Direction.CW)
            clipPath(path)
            content()
        }

        if (faded) {
            applyFade(canvas, crop)
            canvas.restoreToCount(layer)
        }
    }

    private fun applyFade(canvas: Canvas, crop: Crop) {
        if (crop == Crop.NONE) return
        val fade = FADE_DP * density
        val top = crop == Crop.TOP || crop == Crop.BOTH
        val bottom = crop == Crop.BOTTOM || crop == Crop.BOTH
        fadePaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(if (top) Color.TRANSPARENT else Color.BLACK, Color.BLACK, Color.BLACK, if (bottom) Color.TRANSPARENT else Color.BLACK),
            floatArrayOf(0f, fade / height, 1f - fade / height, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
    }

    private fun drawShadow(canvas: Canvas, bounds: RectF, radius: Float, alpha: Float) {
        if (secondPass) return
        val spread = 10f * density
        for (step in 3 downTo 1) {
            val grow = spread * step / 3f
            fill.color = withAlpha(Color.BLACK, 0.035f * alpha)
            rect.set(bounds.left - grow * 0.5f, bounds.top + grow * 0.2f, bounds.right + grow * 0.5f, bounds.bottom + grow)
            canvas.drawRoundRect(rect, radius + grow, radius + grow, fill)
        }
    }

    private fun drawWallpaper(canvas: Canvas) {
        if (wallpaperShader == null) {
            wallpaperShader = LinearGradient(
                0f, 0f, screen.width() * 0.4f, screen.height(),
                intArrayOf(ColorUtils.blendARGB(tertiary, primaryContainer, 0.35f), ColorUtils.blendARGB(primary, primaryContainer, 0.25f), ColorUtils.blendARGB(secondary, onPrimaryContainer, 0.4f)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.withSave {
            translate(screen.left, screen.top)
            shaderPaint.shader = wallpaperShader
            drawRect(0f, 0f, screen.width(), screen.height(), shaderPaint)
        }
        for ((index, blob) in BLOBS.withIndex()) {
            val angle = (progress + index / 3f) * Math.PI.toFloat() * 2f
            val x = screen.left + screen.width() * (blob[0] + 0.18f * sin(angle))
            val y = screen.top + screen.height() * (blob[1] + 0.06f * sin(angle * 2f + 1f))
            val r = screen.width() * blob[2]
            val tint = intArrayOf(tertiaryContainer, primaryContainer, secondaryContainer)[index]
            shaderPaint.shader = RadialGradient(x, y, r, withAlpha(tint, 0.7f), withAlpha(tint, 0f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, shaderPaint)
        }
        shaderPaint.shader = null
    }

    private fun drawStatusBar(canvas: Canvas) {
        val y = screen.top + screen.height() * 0.035f
        val h = screen.height() * 0.012f
        fill.color = withAlpha(Color.WHITE, 0.9f)
        inner.set(screen.left + screen.width() * 0.1f, y - h / 2, screen.left + screen.width() * 0.22f, y + h / 2)
        canvas.drawRoundRect(inner, h / 2, h / 2, fill)
        for (i in 0..2) {
            canvas.drawCircle(screen.right - screen.width() * (0.1f + i * 0.055f), y, h * 0.55f, fill)
        }
    }

    private fun drawGlance(canvas: Canvas, alpha: Float) {
        if (alpha <= 0f) return
        val left = screen.left + gridPad() + screen.width() * 0.03f
        val top = screen.top + screen.height() * 0.085f
        fill.color = withAlpha(Color.WHITE, 0.95f * alpha)
        inner.set(left, top, left + screen.width() * 0.46f, top + screen.height() * 0.028f)
        canvas.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
        fill.color = withAlpha(Color.WHITE, 0.7f * alpha)
        inner.set(left, inner.bottom + screen.height() * 0.014f, left + screen.width() * 0.26f, inner.bottom + screen.height() * 0.03f)
        canvas.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, fill)
    }

    private inline fun drawGrid(
        canvas: Canvas,
        columns: Int,
        rows: Int,
        alpha: Float,
        folder: Pair<Int, Int>? = null,
        folderAlpha: Float = 1f,
        filled: (Int, Int) -> Boolean
    ) {
        if (alpha <= 0f) return
        val size = iconSize(columns, rows)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val (cx, cy) = cellCenter(columns, rows, r, c)
                if (folder != null && folder.first == r && folder.second == c) {
                    drawFolderIcon(canvas, cx, cy, size, folderAlpha * alpha)
                } else if (filled(r, c)) {
                    drawAppIcon(canvas, cx, cy, size, r * columns + c, alpha)
                }
            }
        }
    }

    private fun drawPageIndicator(canvas: Canvas, swipe: Float) {
        val y = gridBottom() + screen.height() * 0.025f
        val gap = screen.width() * 0.05f
        for (i in 0..1) {
            val active = if (i == 0) 1f - swipe else swipe
            fill.color = withAlpha(Color.WHITE, 0.45f + 0.5f * active)
            canvas.drawCircle(screen.centerX() + (i - 0.5f) * gap, y, screen.width() * (0.012f + active * 0.006f), fill)
        }
    }

    private fun drawDockRow(canvas: Canvas, columns: Int, mode: String) {
        if (dockShown <= 0f) return
        val pad = gridPad()
        val cellW = (screen.width() - pad * 2) / columns
        val cy = screen.top + screen.height() * (lerp(0.9f, 0.835f, searchShown) + (1f - dockShown) * 0.06f + navLift())
        val size = iconSize(columns, homeRows())
        val layer = if (dockShown < 1f) canvas.saveLayerAlpha(screen, (dockShown * 255).toInt()) else -1
        if (mode != PanelBackground.OFF) {
            val radius = size * 0.72f
            if (active.dockAttached) {
                rect.set(screen.left, cy - size * 0.9f, screen.right, screen.bottom + radius)
            } else {
                val search = searchShown * dockShown
                val searchBottom = screen.top + screen.height() * (0.92f + (1f - search) * 0.05f + navLift()) + size * 0.46f
                val bottom = lerp(cy + size * 0.75f, searchBottom + size * 0.3f, search)
                rect.set(screen.left + pad * 0.55f, cy - size * 0.75f, screen.right - pad * 0.55f, bottom)
            }
            drawPanel(canvas, rect, radius, mode, native = false, glass = active.dockGlass)
        }
        for (c in 0 until columns) {
            drawAppIcon(canvas, screen.left + pad + cellW * (c + 0.5f), cy, size, DOCK_ICON_OFFSET + c, 1f)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawSearchBar(canvas: Canvas, mode: String) {
        val shown = searchShown * dockShown
        if (shown <= 0f) return
        val layer = if (shown < 1f) canvas.saveLayerAlpha(screen, (shown * 255).toInt()) else -1
        val columns = homeColumns()
        val size = iconSize(columns, homeRows())
        val h = size * 0.92f
        val cy = screen.top + screen.height() * (0.92f + (1f - shown) * 0.05f + navLift())
        val cellW = (screen.width() - gridPad() * 2) / columns
        val edge = gridPad() + cellW / 2 - size / 2
        rect.set(screen.left + edge, cy - h / 2, screen.right - edge, cy + h / 2)
        drawPanel(canvas, rect, h / 2, mode, native = true, glass = active.searchGlass)
        fill.color = primary
        canvas.drawCircle(rect.left + h * 0.55f, cy, h * 0.2f, fill)
        fill.color = withAlpha(if (mode == PanelBackground.OFF) onSurfaceVariant else Color.WHITE, 0.7f)
        canvas.drawCircle(rect.right - h * 0.55f, cy, h * 0.15f, fill)
        canvas.drawCircle(rect.right - h * 1.15f, cy, h * 0.15f, fill)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawNavHandle(canvas: Canvas) {
        if (pillShown <= 0f) return
        fill.color = withAlpha(Color.WHITE, 0.85f * pillShown)
        val y = screen.bottom - screen.height() * 0.018f
        val h = screen.height() * 0.008f
        inner.set(screen.centerX() - screen.width() * 0.16f, y - h / 2, screen.centerX() + screen.width() * 0.16f, y + h / 2)
        canvas.drawRoundRect(inner, h / 2, h / 2, fill)
    }

    private fun drawTouch(canvas: Canvas, x: Float, y: Float, t: Float) {
        val e = emphasized.getInterpolation(t)
        stroke.color = withAlpha(Color.WHITE, (1f - t) * 0.9f)
        stroke.strokeWidth = 2f * density
        canvas.drawCircle(x, y, lerp(8f, 30f, e) * density, stroke)
        drawFinger(canvas, x, y, 1f - t * 0.7f)
    }

    private fun drawFinger(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        if (alpha <= 0f) return
        fill.color = withAlpha(Color.WHITE, 0.4f * alpha)
        canvas.drawCircle(x, y, 11f * density, fill)
        fill.color = withAlpha(Color.WHITE, 0.95f * alpha)
        canvas.drawCircle(x, y, 6.5f * density, fill)
    }

    private fun drawFolderIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Float) {
        if (alpha <= 0f) return
        fill.color = withAlpha(surfaceHigh, alpha)
        canvas.drawCircle(cx, cy, size / 2, fill)
        val mini = size * 0.24f
        for (i in 0..3) {
            val mx = cx + (if (i % 2 == 0) -1 else 1) * mini * 0.6f
            val my = cy + (if (i < 2) -1 else 1) * mini * 0.6f
            fill.color = withAlpha(iconBackgrounds[pairIndex(i)], alpha)
            canvas.drawCircle(mx, my, mini / 2, fill)
        }
    }

    private fun drawAppIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, index: Int, alpha: Float) {
        val pair = pairIndex(index)
        drawIconShape(canvas, cx, cy, size, iconBackgrounds[pair], iconForegrounds[pair], index, alpha)
    }

    private fun pairIndex(index: Int): Int {
        val i = abs(index)
        return (i + i / GLYPH_COUNT) % iconBackgrounds.size
    }

    private fun drawIconShape(canvas: Canvas, cx: Float, cy: Float, size: Float, shapeColor: Int, glyphColor: Int, index: Int, alpha: Float) {
        if (alpha <= 0f) return
        val r = size / 2
        fill.color = withAlpha(shapeColor, alpha)
        canvas.drawCircle(cx, cy, r, fill)

        val g = r * 0.44f
        fill.color = withAlpha(glyphColor, alpha)
        stroke.color = withAlpha(glyphColor, alpha)
        stroke.strokeWidth = maxOf(r * 0.12f, 0.8f * density)
        path.reset()
        when (abs(index) % GLYPH_COUNT) {
            0 -> {
                canvas.drawCircle(cx, cy, g * 0.95f, stroke)
                canvas.drawCircle(cx, cy, g * 0.38f, fill)
            }

            1 -> {
                inner.set(cx - g, cy - g * 0.8f, cx + g, cy + g * 0.5f)
                canvas.drawRoundRect(inner, g * 0.45f, g * 0.45f, fill)
                path.moveTo(cx - g * 0.5f, cy + g * 0.3f)
                path.lineTo(cx - g * 0.7f, cy + g)
                path.lineTo(cx, cy + g * 0.4f)
                path.close()
                canvas.drawPath(path, fill)
            }

            2 -> {
                path.moveTo(cx - g * 0.5f, cy - g * 0.9f)
                path.lineTo(cx + g * 0.95f, cy)
                path.lineTo(cx - g * 0.5f, cy + g * 0.9f)
                path.close()
                canvas.drawPath(path, fill)
            }

            3 -> {
                canvas.drawCircle(cx, cy, g, stroke)
                canvas.drawLine(cx, cy, cx, cy - g * 0.55f, stroke)
                canvas.drawLine(cx, cy, cx + g * 0.45f, cy + g * 0.2f, stroke)
            }

            4 -> {
                inner.set(cx - g, cy - g * 0.7f, cx + g, cy + g * 0.7f)
                canvas.drawRoundRect(inner, g * 0.2f, g * 0.2f, stroke)
                path.moveTo(cx - g * 0.85f, cy - g * 0.5f)
                path.lineTo(cx, cy + g * 0.1f)
                path.lineTo(cx + g * 0.85f, cy - g * 0.5f)
                canvas.drawPath(path, stroke)
            }

            5 -> {
                canvas.drawCircle(cx - g * 0.35f, cy + g * 0.55f, g * 0.38f, fill)
                canvas.drawLine(cx, cy + g * 0.55f, cx, cy - g * 0.9f, stroke)
                canvas.drawLine(cx, cy - g * 0.9f, cx + g * 0.6f, cy - g * 0.55f, stroke)
            }

            6 -> {
                for (k in 0 until 10) {
                    val radius = if (k % 2 == 0) g else g * 0.42f
                    val angle = Math.toRadians(-90.0 + k * 36.0)
                    val x = cx + radius * kotlin.math.cos(angle).toFloat()
                    val y = cy + radius * kotlin.math.sin(angle).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, fill)
            }

            7 -> {
                path.moveTo(cx, cy + g * 0.85f)
                path.cubicTo(cx - g * 1.3f, cy, cx - g * 0.75f, cy - g * 1.05f, cx, cy - g * 0.4f)
                path.cubicTo(cx + g * 0.75f, cy - g * 1.05f, cx + g * 1.3f, cy, cx, cy + g * 0.85f)
                path.close()
                canvas.drawPath(path, fill)
            }

            8 -> {
                path.moveTo(cx, cy + g)
                path.cubicTo(cx - g * 1.1f, cy - g * 0.1f, cx - g * 0.75f, cy - g * 1.05f, cx, cy - g * 1.05f)
                path.cubicTo(cx + g * 0.75f, cy - g * 1.05f, cx + g * 1.1f, cy - g * 0.1f, cx, cy + g)
                path.close()
                canvas.drawPath(path, fill)
                fill.color = withAlpha(shapeColor, alpha)
                canvas.drawCircle(cx, cy - g * 0.35f, g * 0.28f, fill)
            }

            9 -> {
                canvas.drawCircle(cx - g * 0.45f, cy + g * 0.15f, g * 0.45f, fill)
                canvas.drawCircle(cx + g * 0.15f, cy - g * 0.15f, g * 0.6f, fill)
                canvas.drawCircle(cx + g * 0.6f, cy + g * 0.25f, g * 0.38f, fill)
                inner.set(cx - g * 0.45f, cy + g * 0.15f, cx + g * 0.6f, cy + g * 0.63f)
                canvas.drawRect(inner, fill)
            }

            10 -> {
                inner.set(cx - g * 0.9f, cy - g * 0.75f, cx + g * 0.9f, cy + g * 0.85f)
                canvas.drawRoundRect(inner, g * 0.25f, g * 0.25f, stroke)
                inner.set(cx - g * 0.9f, cy - g * 0.75f, cx + g * 0.9f, cy - g * 0.25f)
                canvas.drawRect(inner, fill)
                canvas.drawCircle(cx + g * 0.3f, cy + g * 0.35f, g * 0.2f, fill)
            }

            else -> {
                path.moveTo(cx + g * 0.25f, cy - g)
                path.lineTo(cx - g * 0.65f, cy + g * 0.15f)
                path.lineTo(cx - g * 0.05f, cy + g * 0.15f)
                path.lineTo(cx - g * 0.25f, cy + g)
                path.lineTo(cx + g * 0.65f, cy - g * 0.15f)
                path.lineTo(cx + g * 0.05f, cy - g * 0.15f)
                path.close()
                canvas.drawPath(path, fill)
            }
        }
    }

    private fun drawBlurredBackdrop(canvas: Canvas, bounds: RectF, radius: Float, glass: Glass) {
        val node = backdropNode
        if (recordingBackdrop || node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated) return
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        val savedScreen = RectF(screen)
        recordingBackdrop = true
        backdrop(recording)
        recordingBackdrop = false
        screen.set(savedScreen)
        node.endRecording()
        val blurPx = glass.blur * density * (screen.width() / (REFERENCE_SCREEN_DP * density)) * BLUR_BOOST
        node.setRenderEffect(if (blurPx > 0.5f) RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP) else null)
        canvas.withSave {
            path.reset()
            path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
            clipPath(path)
            drawRenderNode(node)
        }
    }

    private fun drawPanel(canvas: Canvas, bounds: RectF, radius: Float, mode: String, native: Boolean, glass: Glass) {
        if (mode != PanelBackground.OFF) drawBlurredBackdrop(canvas, bounds, radius, glass)
        val tint = glass.tint / 100f
        when (mode) {
            PanelBackground.FROSTED -> {
                fill.color = withAlpha(surface, tint * 0.75f)
                canvas.drawRoundRect(bounds, radius, radius, fill)
                stroke.color = withAlpha(Color.WHITE, 0.3f)
                stroke.strokeWidth = 1f * density
                canvas.drawRoundRect(bounds, radius, radius, stroke)
            }

            PanelBackground.LIQUID -> {
                fill.color = withAlpha(surface, tint * 0.45f)
                canvas.drawRoundRect(bounds, radius, radius, fill)
                stroke.shader = LinearGradient(
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    intArrayOf(withAlpha(Color.WHITE, 0.95f), withAlpha(Color.WHITE, 0.05f), withAlpha(Color.WHITE, 0.7f)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                stroke.strokeWidth = 1.5f * density
                canvas.drawRoundRect(bounds, radius, radius, stroke)
                stroke.shader = null
            }

            else -> if (native) {
                fill.color = surfaceHigh
                canvas.drawRoundRect(bounds, radius, radius, fill)
            }
        }
    }

    private fun fullDevice() {
        val h = height - DEVICE_INSET_DP * 2 * density
        val w = h * DEVICE_ASPECT
        device.set(width / 2f - w / 2, DEVICE_INSET_DP * density, width / 2f + w / 2, DEVICE_INSET_DP * density + h)
    }

    private fun deviceBleeding(crop: Crop, visibleFrom: Float, visibleTo: Float) {
        val inset = DEVICE_INSET_DP * density
        val available = height - when (crop) {
            Crop.TOP -> inset
            Crop.BOTTOM -> inset
            else -> 0f
        }
        val h = available / (visibleTo - visibleFrom)
        val w = h * DEVICE_ASPECT
        val top = when (crop) {
            Crop.TOP -> height - inset - h
            Crop.BOTTOM -> inset
            else -> -visibleFrom * h
        }
        device.set(width / 2f - w / 2, top, width / 2f + w / 2, top + h)
    }

    private fun gridPad() = screen.width() * 0.07f

    private fun gridTop() = screen.top + screen.height() * 0.2f

    private fun gridBottom() = screen.top + screen.height() * (0.86f - 0.06f * dockShown - 0.06f * dockShown * searchShown + navLift())

    private fun navLift() = (1f - navSpaceShown) * NAV_SPACE_FRACTION

    private fun navSpaceHidden() = RPrefs.getBoolean(HIDE_GESTURE_PILL, false) && RPrefs.getBoolean(HIDE_NAVIGATION_SPACE, false)

    private fun cellCenter(columns: Int, rows: Int, r: Int, c: Int): Pair<Float, Float> {
        val pad = gridPad()
        val cellW = (screen.width() - pad * 2) / columns
        val cellH = (gridBottom() - gridTop()) / rows
        return (screen.left + pad + cellW * (c + 0.5f)) to (gridTop() + cellH * (r + 0.5f))
    }

    private fun iconSize(columns: Int, rows: Int): Float {
        val cellW = (screen.width() - gridPad() * 2) / columns
        val cellH = (gridBottom() - gridTop()) / rows
        return min(cellW, cellH) * 0.66f
    }

    private fun phase(openStart: Float, openEnd: Float, closeStart: Float, closeEnd: Float): Float = when {
        progress < openStart -> 0f
        progress < openEnd -> emphasized.getInterpolation((progress - openStart) / (openEnd - openStart))
        progress < closeStart -> 1f
        progress < closeEnd -> 1f - emphasized.getInterpolation((progress - closeStart) / (closeEnd - closeStart))
        else -> 0f
    }

    private fun homeColumns() = active.columns

    private fun homeRows() = active.rows

    private fun dockMode() = active.dockMode

    private fun searchMode() = active.searchMode

    private fun dockHidden() = RPrefs.getBoolean(DISABLE_DOCK, false)

    private fun searchHidden() = RPrefs.getBoolean(DESKTOP_SEARCH_BAR, false)

    private fun color(attr: Int) = MaterialColors.getColor(context, attr, Color.GRAY)

    private fun withAlpha(color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt())

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private const val DEFAULT_COLUMNS = 4
        private const val GLYPH_COUNT = 12
        private const val DEFAULT_BLUR = 5
        private const val DEFAULT_TINT = 40
        private const val REFERENCE_SCREEN_DP = 412f
        private const val BLUR_BOOST = 2.5f
        private const val NAV_SPACE_FRACTION = 0.035f
        private val CHROME_KEYS = setOf(DESKTOP_SEARCH_BAR, DISABLE_DOCK, HIDE_GESTURE_PILL, HIDE_NAVIGATION_SPACE)
        private const val DOCK_FLOATING = "floating"
        private const val DOCK_ATTACHED = "attached"
        private const val CHROME_DURATION_MS = 500L
        private const val DOCK_ICON_OFFSET = 30
        private const val DEFAULT_ROWS = 5
        private const val DEFAULT_FOLDER_COLUMNS = 3
        private const val DEFAULT_FOLDER_ROWS = 3
        private const val DEVICE_INSET_DP = 18
        private const val DEVICE_ASPECT = 0.47f
        private const val DEVICE_RADIUS = 0.17f
        private const val BEZEL_RATIO = 0.035f
        private const val FADE_DP = 44
        private val BEZEL = Color.rgb(0x1B, 0x1B, 0x1F)
        private val BLOBS = arrayOf(floatArrayOf(0.15f, 0.2f, 0.9f), floatArrayOf(0.9f, 0.55f, 0.85f), floatArrayOf(0.4f, 0.95f, 0.9f))
        private val STATIC_PROGRESS = floatArrayOf(0.15f, 0.2f, 0.6f, 0.55f, 0.6f, 0.2f, 0.2f, 0.05f)
    }
}
