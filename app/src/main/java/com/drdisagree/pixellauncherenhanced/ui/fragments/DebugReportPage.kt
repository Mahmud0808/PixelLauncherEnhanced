package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.animation.LayoutTransition
import androidx.core.view.ViewCompat
import androidx.core.animation.doOnEnd
import android.view.animation.PathInterpolator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_RESPONSE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.databinding.FragmentDebugReportBinding
import com.drdisagree.pixellauncherenhanced.utils.DebugReport
import com.drdisagree.pixellauncherenhanced.utils.HapticUtils.toggleVibrate
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.RootShell
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.shape.MaterialShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.google.android.material.R as MaterialR

class DebugReportPage : Fragment() {

    private enum class Option(val title: Int, val summary: Int, val needsRoot: Boolean) {
        LOGS(R.string.debug_report_include_logs, R.string.debug_report_include_logs_desc, false),
        LAUNCHER_DATA(R.string.debug_report_include_launcher_data, R.string.debug_report_include_launcher_data_desc, true),
        SETTINGS(R.string.debug_report_include_settings, R.string.debug_report_include_settings_desc, false),
        APK(R.string.debug_report_include_apk, 0, false)
    }

    private lateinit var binding: FragmentDebugReportBinding
    private val attachments = ArrayList<Uri>()
    private val enabled = Option.entries.associateWith { true }.toMutableMap()
    private val optionViews = HashMap<Option, View>()
    private val stepViews = HashMap<DebugReport.Step, View>()
    private var rooted = false
    private var reportFile: File? = null
    private var building = false

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { uris ->
        uris.filterNot { it in attachments }.forEach { attachments.add(it) }
        renderAttachments()
    }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        val file = reportFile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } != null
                }.getOrDefault(false)
            }
            Toast.makeText(
                requireContext(),
                if (saved) R.string.debug_report_saved else R.string.debug_report_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentDebugReportBinding.inflate(inflater, container, false)
        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.debug_report_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        RPrefs.clearPrefs(DEBUG_REPORT_REQUEST, DEBUG_REPORT_RESPONSE)

        binding.introShape.apply {
            setShapes(MaterialShapes.CLAM_SHELL, MaterialShapes.CLAM_SHELL)
            setIcon(
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_bug_report),
                MaterialColors.getColor(this, MaterialR.attr.colorOnTertiaryContainer)
            )
            setColors(
                MaterialColors.getColor(this, MaterialR.attr.colorTertiaryContainer),
                MaterialColors.getColor(this, MaterialR.attr.colorOnTertiaryContainer)
            )
        }

        (binding.scrollView.getChildAt(0) as ViewGroup).animateChanges()
        binding.steps.animateChanges()
        binding.findings.animateChanges()
        buildOptions()
        buildSteps()
        binding.addAttachments.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        binding.create.setOnClickListener { createReport() }
        binding.share.setOnClickListener { shareReport() }
        binding.save.setOnClickListener { reportFile?.let { saveDocument.launch(it.name) } }

        RootShell.checkRoot { isRooted ->
            if (!isAdded) return@checkRoot
            rooted = isRooted
            renderOptions()
        }
    }

    private fun buildOptions() {
        val inflater = LayoutInflater.from(requireContext())
        Option.entries.forEachIndexed { index, option ->
            val row = inflater.inflate(R.layout.view_debug_option, binding.options, false)
            row.findViewById<TextView>(R.id.title).setText(option.title)
            row.setOnClickListener { view ->
                if (building || (option.needsRoot && !rooted)) return@setOnClickListener
                enabled[option] = enabled[option] != true
                view.toggleVibrate(enabled[option] == true)
                renderOptions()
            }
            binding.options.addView(row)
            RowBackgrounds.apply(row, index, Option.entries.size)
            optionViews[option] = row
        }
        renderOptions()
    }

    private fun renderOptions() {
        optionViews.forEach { (option, row) ->
            val available = !option.needsRoot || rooted
            val summary = when {
                !available -> getString(R.string.debug_report_needs_root)
                option == Option.APK -> getString(R.string.debug_report_include_apk_desc, launcherApkSizeMb())
                option == Option.LOGS && !rooted -> "${getString(option.summary)} · ${getString(R.string.debug_report_needs_root)}"
                else -> getString(option.summary)
            }
            row.findViewById<TextView>(R.id.summary).text = summary
            row.findViewById<MaterialSwitch>(R.id.switchView).apply {
                isChecked = available && enabled[option] == true
                isEnabled = available && !building
            }
            row.alpha = if (available) 1f else DISABLED_ALPHA
        }
    }

    private fun launcherApkSizeMb(): Int {
        val packageManager = requireContext().packageManager
        val info = listOf(PIXEL_LAUNCHER_PACKAGE, LAUNCHER3_PACKAGE).firstNotNullOfOrNull { packageName ->
            runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        } ?: return 0
        val bytes = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).sumOf { File(it).length() }
        return (bytes / (1024 * 1024)).toInt()
    }

    private fun renderAttachments() {
        binding.attachments.removeAllViews()
        attachments.forEach { uri ->
            val chip = Chip(requireContext()).apply {
                text = displayName(uri)
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    attachments.remove(uri)
                    renderAttachments()
                }
            }
            binding.attachments.addView(chip)
        }
        binding.attachments.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun displayName(uri: Uri): String = runCatching {
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun buildSteps() {
        val inflater = LayoutInflater.from(requireContext())
        DebugReport.Step.entries.forEach { step ->
            val row = inflater.inflate(R.layout.view_debug_step, binding.steps, false)
            row.findViewById<TextView>(R.id.title).setText(STEP_TITLES.getValue(step))
            (row as ViewGroup).animateChanges()
            (row.findViewById<TextView>(R.id.title).parent as ViewGroup).animateChanges()
            binding.steps.addView(row)
            stepViews[step] = row
            renderStep(step, DebugReport.Status.PENDING, null)
        }
    }

    private fun renderStep(step: DebugReport.Step, status: DebugReport.Status, detail: String?) {
        val row = stepViews[step] ?: return
        val icon = row.findViewById<ImageView>(R.id.status_icon)
        val progress = row.findViewById<CircularProgressIndicator>(R.id.status_progress)
        val (drawable, color) = when (status) {
            DebugReport.Status.PENDING -> R.drawable.ic_debug_radio_button_unchecked to MaterialR.attr.colorOutline
            DebugReport.Status.RUNNING -> 0 to androidx.appcompat.R.attr.colorPrimary
            DebugReport.Status.DONE -> R.drawable.ic_debug_check_circle to androidx.appcompat.R.attr.colorPrimary
            DebugReport.Status.SKIPPED -> R.drawable.ic_debug_do_not_disturb_on to MaterialR.attr.colorOnSurfaceVariant
            DebugReport.Status.FAILED -> R.drawable.ic_debug_error to androidx.appcompat.R.attr.colorError
        }
        progress.visibility = if (status == DebugReport.Status.RUNNING) View.VISIBLE else View.GONE
        icon.visibility = if (status == DebugReport.Status.RUNNING) View.INVISIBLE else View.VISIBLE
        if (drawable != 0) icon.setImageResource(drawable)
        icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, color))
        row.findViewById<TextView>(R.id.detail).apply {
            text = detail
            visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun smoothNestedScroll(distance: Int) {
        if (distance <= 0) return
        val scrollView = binding.scrollView
        val consumed = IntArray(2)
        var travelled = 0
        scrollView.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_NON_TOUCH)
        ValueAnimator.ofInt(0, distance).apply {
            duration = SCROLL_DURATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                val step = animator.animatedValue as Int - travelled
                travelled += step
                consumed[1] = 0
                scrollView.dispatchNestedPreScroll(0, step, consumed, null, ViewCompat.TYPE_NON_TOUCH)
                scrollView.scrollBy(0, step - consumed[1])
            }
            doOnEnd { scrollView.stopNestedScroll(ViewCompat.TYPE_NON_TOUCH) }
            start()
        }
    }

    private fun ViewGroup.animateChanges() {
        layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(LAYOUT_ANIMATION_MS)
        }
    }

    private fun createReport() {
        if (building) return
        building = true
        reportFile = null
        binding.create.isEnabled = false
        binding.addAttachments.isEnabled = false
        binding.descriptionLayout.isEnabled = false
        binding.resultCard.visibility = View.GONE
        binding.progressCard.visibility = View.VISIBLE
        DebugReport.Step.entries.forEach { renderStep(it, DebugReport.Status.PENDING, null) }
        renderOptions()
        binding.progressCard.doOnNextLayout { card ->
            val scrollView = binding.scrollView
            val appBar = binding.header.appBarLayout
            val appBarRemaining = (appBar.totalScrollRange + appBar.top).coerceAtLeast(0)
            val target = card.top - binding.create.height - scrollView.scrollY + appBarRemaining
            smoothNestedScroll(target)
        }

        val options = DebugReport.Options(
            includeApk = enabled[Option.APK] == true,
            includeLogs = enabled[Option.LOGS] == true,
            includeLauncherData = rooted && enabled[Option.LAUNCHER_DATA] == true,
            includeSettings = enabled[Option.SETTINGS] == true,
            description = binding.description.text?.toString().orEmpty(),
            attachments = attachments.toList()
        )
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                DebugReport(appContext).build(options) { step, status, detail -> renderStep(step, status, detail) }
            }
            building = false
            binding.create.isEnabled = true
            binding.addAttachments.isEnabled = true
            binding.descriptionLayout.isEnabled = true
            renderOptions()
            result.onSuccess { showResult(it) }.onFailure {
                Toast.makeText(requireContext(), R.string.debug_report_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showResult(result: DebugReport.Result) {
        reportFile = result.file
        binding.resultName.text = result.file.name
        binding.resultSize.text = android.text.format.Formatter.formatShortFileSize(requireContext(), result.file.length())
        renderFindings(result.findings)
        binding.resultCard.visibility = View.VISIBLE
    }

    private fun renderFindings(findings: List<DebugReport.Finding>) {
        binding.findings.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val rows = findings.ifEmpty { listOf(DebugReport.Finding(DebugReport.Severity.INFO, getString(R.string.debug_report_no_findings))) }
        rows.forEach { finding ->
            val row = inflater.inflate(R.layout.view_debug_step, binding.findings, false)
            row.findViewById<TextView>(R.id.title).text = finding.title
            val icon = row.findViewById<ImageView>(R.id.status_icon)
            val (drawable, color) = when {
                findings.isEmpty() -> R.drawable.ic_debug_check_circle to androidx.appcompat.R.attr.colorPrimary
                finding.severity == DebugReport.Severity.ERROR -> R.drawable.ic_debug_error to androidx.appcompat.R.attr.colorError
                finding.severity == DebugReport.Severity.WARNING -> R.drawable.ic_debug_warning to MaterialR.attr.colorTertiary
                else -> R.drawable.ic_info to MaterialR.attr.colorOnSurfaceVariant
            }
            icon.setImageResource(drawable)
            icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(icon, color))
            binding.findings.addView(row)
        }
    }

    private fun shareReport() {
        val file = reportFile ?: return
        val uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.debugreports", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = ZIP_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.debug_report_share)))
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            parentFragmentManager.popBackStackImmediate()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val ZIP_MIME = "application/zip"
        private const val MAX_ATTACHMENTS = 10
        private const val DISABLED_ALPHA = 0.5f
        private const val LAYOUT_ANIMATION_MS = 250L
        private const val SCROLL_DURATION_MS = 600L
        private val STEP_TITLES = mapOf(
            DebugReport.Step.DEVICE to R.string.debug_step_device,
            DebugReport.Step.ROOT to R.string.debug_step_root,
            DebugReport.Step.LAUNCHER to R.string.debug_step_launcher,
            DebugReport.Step.RUNTIME to R.string.debug_step_runtime,
            DebugReport.Step.LAUNCHER_STATE to R.string.debug_step_launcher_state,
            DebugReport.Step.LOGS to R.string.debug_step_logs,
            DebugReport.Step.SETTINGS to R.string.debug_step_settings,
            DebugReport.Step.APK to R.string.debug_step_apk,
            DebugReport.Step.ATTACHMENTS to R.string.debug_step_attachments,
            DebugReport.Step.PACKAGE to R.string.debug_step_package
        )
    }
}
