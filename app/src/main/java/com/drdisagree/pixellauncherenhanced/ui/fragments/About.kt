package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.PLEnhanced.Companion.appContext
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.databinding.FragmentAboutBinding
import com.drdisagree.pixellauncherenhanced.utils.HapticUtils.weakVibrate
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.setupToolbar
import com.drdisagree.pixellauncherenhanced.utils.Presets
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.xml.image.ImageUtil
import com.google.android.material.R as MaterialR

class About : Fragment() {

    private lateinit var binding: FragmentAboutBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutBinding.inflate(inflater, container, false)

        setupToolbar(
            requireContext() as AppCompatActivity,
            R.string.fragment_about_title,
            true,
            binding.header.toolbar,
            binding.header.collapsingToolbar
        )

        try {
            binding.appIcon.setImageDrawable(
                requireContext().packageManager.getApplicationIcon(
                    BuildConfig.APPLICATION_ID
                )
            )
        } catch (_: PackageManager.NameNotFoundException) {
            // Unlikely to happen, but just in case
            binding.appIcon.setImageResource(R.mipmap.ic_launcher)
        }
        binding.appIcon.setOnClickListener { view ->
            view.weakVibrate()
            pulseBackdrop()
            launchRandomKonfetti()
        }
        binding.iconBackdrop.setOnClickListener { view ->
            view.weakVibrate()
            pulseBackdrop()
            launchRandomKonfetti()
        }
        setupBackdrop()
        binding.versionCode.background = GradientDrawable().apply {
            cornerRadius = Float.MAX_VALUE
            setColor(MaterialColors.getColor(binding.versionCode, MaterialR.attr.colorSecondaryContainer))
        }
        binding.versionCode.text = getString(
            R.string.version_codes,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        binding.btnNews.setOnClickListener { openUrl("https://t.me/DrDsProjects") }
        binding.btnSupport.setOnClickListener { openUrl("https://t.me/DrDsProjectsChat") }
        binding.btnGithub.setOnClickListener { openUrl("https://github.com/Mahmud0808/PixelLauncherEnhanced") }
        binding.developer.setOnClickListener { openUrl("https://github.com/Mahmud0808") }
        binding.buymeacoffee.setOnClickListener { openUrl("https://buymeacoffee.com/drdisagree") }

        return binding.root
    }

    private fun setupBackdrop() {
        val backdrop = binding.iconBackdrop
        backdrop.setShapes(MaterialShapes.COOKIE_7, MaterialShapes.SOFT_BOOM)
        backdrop.setIcon(null, 0)
        val color = MaterialColors.getColor(backdrop, MaterialR.attr.colorPrimaryContainer)
        backdrop.setColors(color, color)
        (binding.appIcon.drawable as? AdaptiveIconDrawable)?.let {
            backdrop.setAdaptiveIcon(it)
            binding.appIcon.visibility = View.GONE
        }
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val spin = ValueAnimator.ofFloat(0f, 360f).apply {
            addUpdateListener { backdrop.shapeRotation = it.animatedValue as Float }
            duration = BACKDROP_SPIN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        backdrop.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (spin.isPaused) spin.resume() else if (!spin.isStarted) spin.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                spin.pause()
            }
        })
    }

    private fun pulseBackdrop() {
        val backdrop = binding.iconBackdrop
        backdrop.setPressedState(true)
        backdrop.postDelayed({ backdrop.setPressedState(false) }, HEART_HOLD_MS)
    }

    private fun openUrl(url: String) {
        try {
            requireContext().startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    url.toUri()
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun launchRandomKonfetti() {
        val delayDuration = resources.getInteger(android.R.integer.config_mediumAnimTime) + 100

        viewLifecycleOwner.lifecycleScope.launch {
            delay(delayDuration.toLong())

            val drawable = AppCompatResources.getDrawable(appContext, R.drawable.ic_heart)
            val drawableShape = ImageUtil.loadDrawable(drawable!!)

            val effects = listOf(
                { binding.konfettiView.start(Presets.explode()) },
                { binding.konfettiView.start(Presets.parade()) },
                { binding.konfettiView.start(Presets.rain()) },
                { binding.konfettiView.start(Presets.festive(drawableShape)) }
            )

            effects.random().invoke()
        }
    }

    companion object {
        private const val BACKDROP_SPIN_MS = 60000L
        private const val HEART_HOLD_MS = 900L
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
}