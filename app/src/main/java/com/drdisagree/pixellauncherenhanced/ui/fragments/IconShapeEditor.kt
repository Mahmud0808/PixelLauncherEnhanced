package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.content.pm.LauncherApps
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SHAPE_CUSTOM
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SHAPE_CUSTOM_OPS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SHAPE_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SHAPE_PARAMS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SHAPE_PATH
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.enums.IconShapeMode
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeSketchError
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeSymmetry
import com.drdisagree.pixellauncherenhanced.data.enums.ShapeTool
import com.drdisagree.pixellauncherenhanced.data.model.ShapeDesign
import com.drdisagree.pixellauncherenhanced.data.model.ShapeOp
import com.drdisagree.pixellauncherenhanced.data.model.ShapeParam
import com.drdisagree.pixellauncherenhanced.data.model.ShapeSketchResult
import com.drdisagree.pixellauncherenhanced.databinding.FragmentIconShapeBinding
import com.drdisagree.pixellauncherenhanced.databinding.ViewShapeSliderBinding
import com.drdisagree.pixellauncherenhanced.databinding.ViewShapeTileBinding
import com.drdisagree.pixellauncherenhanced.ui.widgets.IconShapePreviewView
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeDrawView
import com.drdisagree.pixellauncherenhanced.utils.IconShapeFactory
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.ShapeComposer
import com.drdisagree.pixellauncherenhanced.utils.ShapeDesignCodec
import com.drdisagree.pixellauncherenhanced.utils.ShapeSketch
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IconShapeEditor : Fragment() {

    private lateinit var binding: FragmentIconShapeBinding

    private var mode = IconShapeMode.DEFAULT
    private var params: MutableMap<String, Int> = HashMap()
    private var customPath = ""
    private val ops = ArrayList<ShapeOp>()
    private val undoStack = ArrayList<List<ShapeOp>>()
    private val redoStack = ArrayList<List<ShapeOp>>()
    private var dragSnapshot: List<ShapeOp>? = null
    private var symmetry = ShapeSymmetry.NONE
    private var coversSafeZone = true
    private var designError: Int? = null
    private var composeJob: Job? = null
    private val tiles = HashMap<IconShapeMode, ViewShapeTileBinding>()
    private val sliders = HashMap<String, ViewShapeSliderBinding>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentIconShapeBinding.inflate(inflater, container, false)

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.fragment_icon_shape_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mode = IconShapeMode.fromValue(RPrefs.getInt(ICON_SHAPE_MODE, IconShapeMode.DEFAULT.value))
        params = IconShapeFactory.decode(RPrefs.getString(ICON_SHAPE_PARAMS, ""))
        customPath = RPrefs.getString(ICON_SHAPE_CUSTOM, "").orEmpty()

        buildTiles()

        setupDesigner()

        binding.apply.setOnClickListener { applyShape() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.scrollContent.updatePadding(bottom = dpToPx(112) + navBarInset)
            binding.apply.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dpToPx(24) + navBarInset
            }

            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        updateTileShapes()
        updateTileSelection(animate = false)
        render(animate = false)
        loadSampleIcons()
    }

    private fun buildTiles() {
        val inflater = LayoutInflater.from(requireContext())
        binding.tiles.removeAllViews()
        tiles.clear()

        IconShapeMode.ordered.forEach { entry ->
            val tile = ViewShapeTileBinding.inflate(inflater, binding.tiles, false)
            tile.label.setText(entry.label)
            tile.root.setOnClickListener {
                if (mode == entry) return@setOnClickListener
                mode = entry
                updateTileSelection(animate = true)
                render(animate = true)
            }
            binding.tiles.addView(tile.root)
            tiles[entry] = tile
        }
    }

    private fun render(animate: Boolean) {
        binding.sliderCard.isVisible = mode.params.isNotEmpty()
        binding.customCard.isVisible = mode == IconShapeMode.CUSTOM

        buildControls()

        if (mode == IconShapeMode.CUSTOM) {
            binding.drawPad.showComposed(customPath.takeIf { it.isNotEmpty() })
            showDesignStatus()
        }

        updatePreview(animate)
    }

    private fun buildControls() {
        val inflater = LayoutInflater.from(requireContext())
        binding.controls.removeAllViews()
        sliders.clear()

        if (mode == IconShapeMode.CORNERS) {
            binding.controls.addView(
                MaterialSwitch(requireContext()).apply {
                    setText(R.string.icon_shape_link_corners)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    isChecked = params[IconShapeFactory.CORNERS_LINKED] == 1
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dpToPx(8) }
                    setOnCheckedChangeListener { _, checked ->
                        params[IconShapeFactory.CORNERS_LINKED] = if (checked) 1 else 0
                        if (checked) linkCorners(mode.params.first().let { valueOf(it) })
                    }
                }
            )
        }

        mode.params.forEach { param ->
            val row = ViewShapeSliderBinding.inflate(inflater, binding.controls, false)
            row.title.setText(param.title)
            row.slider.valueFrom = param.from.toFloat()
            row.slider.valueTo = param.to.toFloat()
            row.slider.stepSize = 1f
            row.slider.value = valueOf(param).toFloat()
            row.min.text = param.minLabel?.let { getString(it) } ?: param.format(param.from)
            row.max.text = param.maxLabel?.let { getString(it) } ?: param.format(param.to)
            row.value.text = param.format(valueOf(param))
            row.slider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val intValue = value.toInt()
                if (mode == IconShapeMode.CORNERS && params[IconShapeFactory.CORNERS_LINKED] == 1) {
                    linkCorners(intValue)
                } else {
                    params[param.key] = intValue
                    row.value.text = param.format(intValue)
                }
                onShapeChanged()
            }
            binding.controls.addView(row.root)
            sliders[param.key] = row
        }
    }

    private fun linkCorners(value: Int) {
        IconShapeMode.CORNERS.params.forEach { param ->
            params[param.key] = value
            sliders[param.key]?.apply {
                slider.value = value.toFloat()
                this.value.text = param.format(value)
            }
        }
        onShapeChanged()
    }

    private fun valueOf(param: ShapeParam): Int = (params[param.key] ?: param.default).coerceIn(param.from, param.to)

    private fun onShapeChanged() {
        updatePreview(animate = false)
        tiles[mode]?.shape?.setShape(currentPath(mode))
    }

    private fun updateTileSelection(animate: Boolean) {
        tiles.forEach { (entry, tile) ->
            val checked = entry == mode
            tile.shape.setChecked(checked, animate)
            tile.label.isSelected = checked
            tile.root.isSelected = checked
        }
    }

    private fun updateTileShapes() {
        tiles.forEach { (entry, tile) ->
            tile.shape.setShape(if (entry == IconShapeMode.DEFAULT) null else currentPath(entry))
        }
    }

    private fun updatePreview(animate: Boolean) {
        binding.preview.setShape(currentPath(mode), animate)
        binding.previewSummary.setText(
            if (mode == IconShapeMode.CUSTOM && customPath.isEmpty()) R.string.icon_shape_summary_custom_empty
            else mode.summary
        )
        updateApplyButton()
    }

    private fun updateApplyButton() {
        val valid = mode != IconShapeMode.CUSTOM || customPath.isNotEmpty()
        val pending = valid && currentPath(mode) != RPrefs.getString(ICON_SHAPE_PATH, "").orEmpty()
        if (pending) binding.apply.show() else binding.apply.hide()
    }

    private fun currentPath(target: IconShapeMode): String = IconShapeFactory.path(target, params, customPath)

    private fun applyShape() {
        if (mode == IconShapeMode.CUSTOM && customPath.isEmpty()) return

        RPrefs.putInt(ICON_SHAPE_MODE, mode.value)
        RPrefs.putString(ICON_SHAPE_PARAMS, IconShapeFactory.encode(params))
        RPrefs.putString(ICON_SHAPE_CUSTOM, customPath)
        RPrefs.putString(ICON_SHAPE_CUSTOM_OPS, ShapeDesignCodec.encode(ShapeDesign(ops.toList(), symmetry)))
        RPrefs.putString(ICON_SHAPE_PATH, currentPath(mode))

        Toast.makeText(requireContext(), R.string.icon_shape_applied, Toast.LENGTH_SHORT).show()
        updateApplyButton()
    }

    private fun setupDesigner() {
        val stored = ShapeDesignCodec.decode(RPrefs.getString(ICON_SHAPE_CUSTOM_OPS, ""))
        ops.clear()
        ops.addAll(stored.ops)
        symmetry = stored.symmetry

        binding.drawPad.symmetry = symmetry
        binding.drawPad.ops = ops.toList()
        binding.tools.check(R.id.toolOutline)
        binding.symmetry.check(
            when (symmetry) {
                ShapeSymmetry.NONE -> R.id.symmetryOff
                ShapeSymmetry.MIRROR -> R.id.symmetryMirror
                ShapeSymmetry.QUAD -> R.id.symmetryQuad
            }
        )

        binding.tools.setOnCheckedStateChangeListener { _, checked ->
            binding.drawPad.tool = when (checked.firstOrNull()) {
                R.id.toolSelect -> ShapeTool.SELECT
                R.id.toolCircle -> ShapeTool.CIRCLE
                R.id.toolSquare -> ShapeTool.SQUARE
                R.id.toolRing -> ShapeTool.RING
                else -> ShapeTool.OUTLINE
            }
        }

        binding.brushMode.check(R.id.modeAdd)
        binding.brushMode.setOnCheckedStateChangeListener { _, checked ->
            binding.drawPad.addMode = checked.firstOrNull() != R.id.modeCut
        }

        binding.symmetry.setOnCheckedStateChangeListener { _, checked ->
            symmetry = when (checked.firstOrNull()) {
                R.id.symmetryMirror -> ShapeSymmetry.MIRROR
                R.id.symmetryQuad -> ShapeSymmetry.QUAD
                else -> ShapeSymmetry.NONE
            }
            binding.drawPad.symmetry = symmetry
            recompose()
        }

        binding.drawPad.listener = object : ShapeDrawView.Listener {
            override fun onOutlineFinished(points: List<PointF>) {
                when (val result = ShapeSketch.close(points)) {
                    is ShapeSketchResult.Success -> addOp(ShapeOp.Outline(result.outline, binding.drawPad.addMode))
                    is ShapeSketchResult.Failure -> {
                        designError = result.error.message
                        showDesignStatus()
                    }
                }
            }

            override fun onStampFinished(tool: ShapeTool, bounds: RectF) {
                addOp(ShapeOp.Stamp(tool, bounds, binding.drawPad.addMode))
            }

            override fun onOpTransformed(index: Int, op: ShapeOp, finished: Boolean) {
                if (index !in ops.indices) return
                if (dragSnapshot == null) dragSnapshot = ops.toList()
                ops[index] = op
                if (finished) {
                    dragSnapshot?.let { snapshot ->
                        undoStack.add(snapshot)
                        redoStack.clear()
                    }
                    dragSnapshot = null
                }
                binding.drawPad.ops = ops.toList()
                recompose()
            }

            override fun onOpDeleted(index: Int) {
                if (index !in ops.indices) return
                commit(ops.toMutableList().apply { removeAt(index) })
            }
        }

        binding.undo.setOnClickListener {
            val previous = undoStack.removeLastOrNull() ?: return@setOnClickListener
            redoStack.add(ops.toList())
            restore(previous)
        }

        binding.redo.setOnClickListener {
            val next = redoStack.removeLastOrNull() ?: return@setOnClickListener
            undoStack.add(ops.toList())
            restore(next)
        }

        binding.clearDrawing.setOnClickListener {
            if (ops.isNotEmpty()) commit(emptyList())
        }

        updateHistoryButtons()
        if (ops.isNotEmpty()) recompose()
    }

    private fun addOp(op: ShapeOp) {
        commit(ops + op)
        binding.drawPad.selected = ops.lastIndex
    }

    private fun commit(next: List<ShapeOp>) {
        undoStack.add(ops.toList())
        redoStack.clear()
        restore(next)
    }

    private fun restore(next: List<ShapeOp>) {
        ops.clear()
        ops.addAll(next)
        binding.drawPad.selected = -1
        binding.drawPad.ops = ops.toList()
        recompose()
    }

    private fun recompose() {
        updateHistoryButtons()
        designError = null
        composeJob?.cancel()

        val design = ShapeDesign(ops.toList(), symmetry)
        composeJob = viewLifecycleOwner.lifecycleScope.launch {
            val composed = withContext(Dispatchers.Default) { ShapeComposer.compose(design) }
            customPath = composed?.pathData.orEmpty()
            coversSafeZone = composed?.coversSafeZone ?: true
            if (composed == null && ops.isNotEmpty()) designError = ShapeSketchError.TOO_SMALL.message
            binding.drawPad.showComposed(customPath)
            showDesignStatus()
            onShapeChanged()
        }
    }

    private fun updateHistoryButtons() {
        binding.undo.isEnabled = undoStack.isNotEmpty()
        binding.redo.isEnabled = redoStack.isNotEmpty()
        binding.clearDrawing.isEnabled = ops.isNotEmpty()
    }

    private fun showDesignStatus() {
        val error = designError
        when {
            error != null -> showDrawMessage(getString(error))
            customPath.isNotEmpty() && !coversSafeZone -> showDrawMessage(getString(R.string.icon_shape_warning_safe_zone))
            else -> showDrawMessage(null)
        }
    }

    private fun showDrawMessage(message: String?) {
        binding.drawStatus.text = message
        binding.drawStatus.isVisible = message != null
    }

    private fun loadSampleIcons() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) {
                runCatching {
                    val density = context.resources.displayMetrics.densityDpi
                    context.getSystemService(LauncherApps::class.java)
                        .getActivityList(null, Process.myUserHandle())
                        .asSequence()
                        .filter { it.componentName.packageName != context.packageName }
                        .take(SAMPLE_COUNT)
                        .mapNotNull { info ->
                            runCatching {
                                IconShapePreviewView.Sample(info.getIcon(density), info.label)
                            }.getOrNull()
                        }
                        .toList()
                }.getOrDefault(emptyList())
            }
            binding.preview.setSamples(samples)
        }
    }

    companion object {
        private const val SAMPLE_COUNT = 4
    }
}
