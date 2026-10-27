package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.widgets.ShapeIconView
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager
import com.drdisagree.pixellauncherenhanced.utils.UpdateManager.State
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.MaterialShapes
import java.lang.ref.WeakReference
import java.util.Date
import com.google.android.material.R as MaterialR

class UpdateCardPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    enum class Action(@StringRes val label: Int, @DrawableRes val icon: Int) {
        CHECK(R.string.update_action_check, R.drawable.ic_update),
        DOWNLOAD(R.string.update_action_download, R.drawable.ic_download),
        INSTALL(R.string.update_action_install, R.drawable.ic_check),
        CANCEL(R.string.update_action_cancel, R.drawable.ic_close),
        RETRY(R.string.update_action_retry, R.drawable.ic_update),
        RELEASES(R.string.update_action_releases, R.drawable.ic_open),
        DEV_DOWNLOAD(R.string.update_action_download, R.drawable.ic_download),
        BUILD(R.string.update_action_build, R.drawable.ic_github)
    }

    var onAction: ((Action) -> Unit)? = null
    var onSkip: (() -> Unit)? = null

    fun refresh() {
        boundView?.get()?.let { bind(it) } ?: notifyChanged()
    }

    var state: State = State.Idle
        set(value) {
            if (field == value) return
            field = value
            boundView?.get()?.let { bind(it) } ?: notifyChanged()
        }

    private var boundView: WeakReference<View>? = null
    private var spinner: ValueAnimator? = null

    init {
        layoutResource = R.layout.preference_update_card
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        boundView = WeakReference(holder.itemView)
        bind(holder.itemView)
    }

    override fun onDetached() {
        spinner?.cancel()
        spinner = null
        super.onDetached()
    }

    private fun bind(root: View) {
        val current = state
        val failed = current is State.Failed
        val busy = current is State.Checking || current is State.Downloading

        bindEmblem(root.findViewById(R.id.update_emblem), current, failed, busy)

        root.findViewById<TextView>(R.id.update_status).setText(statusText(current))
        root.findViewById<TextView>(R.id.update_detail).text = detailText(current)

        root.findViewById<TextView>(R.id.update_version).apply {
            val pill = MaterialColors.getColor(this, MaterialR.attr.colorSecondaryContainer)
            text = context.getString(R.string.update_installed_version, UpdateManager.installedVersion)
            setTextColor(MaterialColors.getColor(this, MaterialR.attr.colorOnSecondaryContainer))
            background = GradientDrawable().apply {
                cornerRadius = Float.MAX_VALUE
                setColor(pill)
            }
        }

        bindProgress(root.findViewById(R.id.update_progress), current)
        root.findViewById<View>(R.id.update_dev_notice).visibility =
            if (UpdateManager.isDevBuild) View.VISIBLE else View.GONE

        val (primary, secondary) = actions(current)
        bindButton(root.findViewById(R.id.update_primary), primary, current !is State.Checking)
        bindButton(root.findViewById(R.id.update_secondary), secondary, true)

        val skippable = when (current) {
            is State.Available -> current.release
            is State.Ready -> current.release
            else -> null
        }?.let { !UpdateManager.isSkipped(it) } ?: false
        root.findViewById<MaterialButton>(R.id.update_skip).apply {
            visibility = if (skippable) View.VISIBLE else View.GONE
            setOnClickListener { onSkip?.invoke() }
        }
    }

    private fun bindEmblem(emblem: ShapeIconView, current: State, failed: Boolean, busy: Boolean) {
        val container = MaterialColors.getColor(
            emblem,
            if (failed) MaterialR.attr.colorErrorContainer else MaterialR.attr.colorPrimaryContainer
        )
        val content = MaterialColors.getColor(
            emblem,
            if (failed) MaterialR.attr.colorOnErrorContainer else MaterialR.attr.colorOnPrimaryContainer
        )
        if (emblem.tag == null) {
            emblem.setShapes(MaterialShapes.COOKIE_12, MaterialShapes.SUNNY)
            emblem.tag = SHAPE_TAG
        }
        emblem.setColors(container, content)
        emblem.setIcon(AppCompatResources.getDrawable(context, emblemIcon(current)), content)
        emblem.setPressedState(current is State.Available || current is State.Ready || current is State.DevAvailable)

        if (busy && ValueAnimator.areAnimatorsEnabled()) {
            if (spinner?.isRunning != true) {
                spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                    addUpdateListener { emblem.shapeRotation = it.animatedValue as Float }
                    duration = SPIN_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        } else {
            spinner?.cancel()
            spinner = null
        }
    }

    private fun bindProgress(progress: LinearProgressIndicator, current: State) {
        val indeterminate = current is State.Checking ||
                (current is State.Downloading && (current.total <= 0 || current.downloaded <= 0))
        val visible = current is State.Checking || current is State.Downloading
        if (!visible) {
            progress.visibility = View.GONE
            return
        }
        if (progress.isIndeterminate != indeterminate) {
            progress.visibility = View.GONE
            progress.isIndeterminate = indeterminate
        }
        progress.visibility = View.VISIBLE
        if (current is State.Downloading && current.total > 0) {
            progress.max = PROGRESS_MAX
            progress.setProgressCompat((current.downloaded * PROGRESS_MAX / current.total).toInt(), true)
        }
    }

    private fun bindButton(button: MaterialButton, action: Action?, enabled: Boolean) {
        if (action == null) {
            button.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        button.isEnabled = enabled
        button.setText(action.label)
        button.setIconResource(action.icon)
        button.setOnClickListener { onAction?.invoke(action) }
    }

    @StringRes
    private fun statusText(current: State): Int = when (current) {
        State.Idle -> R.string.update_title_idle
        State.Checking -> R.string.update_status_checking
        is State.UpToDate -> if (UpdateManager.installedDevNumber != null) R.string.update_status_dev_latest
        else R.string.update_status_latest

        is State.Available -> R.string.update_status_available
        is State.DevAvailable -> R.string.update_status_dev_available
        is State.Downloading -> R.string.update_status_downloading
        is State.Ready -> R.string.update_status_ready
        is State.Failed -> when (current.error) {
            UpdateManager.Error.CHECK -> R.string.update_status_check_failed
            UpdateManager.Error.DOWNLOAD -> R.string.update_status_download_failed
            UpdateManager.Error.SIGNATURE -> R.string.update_status_signature_failed
        }
    }

    private fun detailText(current: State): String = when (current) {
        State.Idle, State.Checking, is State.UpToDate -> lastCheckedText()
        is State.Available -> versionText(current.release.version, current.release.size)
        is State.DevAvailable -> context.getString(
            R.string.update_dev_available_desc,
            current.build.number,
            UpdateManager.installedDevNumber ?: 0
        )

        is State.Ready -> versionText(current.release.version, current.release.size)
        is State.Downloading -> if (current.downloaded <= 0) context.getString(R.string.update_connecting)
        else context.getString(
            R.string.update_progress,
            Formatter.formatShortFileSize(context, current.downloaded),
            Formatter.formatShortFileSize(context, current.total.coerceAtLeast(current.downloaded))
        )

        is State.Failed -> when (current.error) {
            UpdateManager.Error.CHECK -> current.problem?.let { problemText(it) } ?: lastCheckedText()
            UpdateManager.Error.DOWNLOAD -> current.problem?.let { problemText(it) }
                ?: current.release?.let { versionText(it.version, it.size) }.orEmpty()

            UpdateManager.Error.SIGNATURE -> context.getString(R.string.update_signature_failed_desc)
        }
    }

    private fun problemText(problem: UpdateManager.Problem): String = when (problem.reason) {
        UpdateManager.Reason.OFFLINE -> context.getString(R.string.update_problem_offline)
        UpdateManager.Reason.UNREACHABLE -> context.getString(R.string.update_problem_unreachable)
        UpdateManager.Reason.TIMEOUT -> context.getString(R.string.update_problem_timeout)
        UpdateManager.Reason.SECURE -> context.getString(R.string.update_problem_secure)
        UpdateManager.Reason.RATE_LIMIT -> if (problem.retryAt > System.currentTimeMillis()) {
            context.getString(
                R.string.update_problem_rate_limit_until,
                DateFormat.getTimeFormat(context).format(Date(problem.retryAt))
            )
        } else {
            context.getString(R.string.update_problem_rate_limit)
        }

        UpdateManager.Reason.SERVER -> context.getString(R.string.update_problem_server, problem.code)
        UpdateManager.Reason.HTTP -> context.getString(R.string.update_problem_http, problem.code)
        UpdateManager.Reason.NO_APK -> context.getString(R.string.update_problem_no_apk)
        UpdateManager.Reason.UNKNOWN -> context.getString(R.string.update_problem_unknown)
    }

    private fun versionText(version: String, size: Long): String =
        if (size > 0) context.getString(R.string.update_version_size, version, Formatter.formatShortFileSize(context, size))
        else context.getString(R.string.update_version, version)

    private fun lastCheckedText(): String {
        val last = UpdateManager.lastCheck
        if (last <= 0L) return context.getString(R.string.update_never_checked)
        val relative = DateUtils.getRelativeTimeSpanString(
            last,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        return context.getString(R.string.update_last_checked, relative)
    }

    @DrawableRes
    private fun emblemIcon(current: State): Int = when (current) {
        is State.UpToDate -> R.drawable.ic_check
        is State.Available, is State.DevAvailable, is State.Downloading, is State.Ready -> R.drawable.ic_download
        is State.Failed -> R.drawable.ic_error
        else -> R.drawable.ic_update
    }

    private fun actions(current: State): Pair<Action?, Action?> = when (current) {
        State.Idle, State.Checking, is State.UpToDate -> Action.CHECK to null
        is State.Available -> Action.DOWNLOAD to Action.RELEASES
        is State.DevAvailable -> Action.DEV_DOWNLOAD to Action.BUILD
        is State.Downloading -> Action.CANCEL to null
        is State.Ready -> Action.INSTALL to Action.RELEASES
        is State.Failed -> when (current.error) {
            UpdateManager.Error.CHECK -> Action.RETRY to null
            UpdateManager.Error.DOWNLOAD -> Action.RETRY to Action.RELEASES
            UpdateManager.Error.SIGNATURE -> Action.RELEASES to null
        }
    }

    companion object {
        private const val SPIN_MS = 6000L
        private const val PROGRESS_MAX = 1000
        private const val SHAPE_TAG = "update_shape"
    }
}
