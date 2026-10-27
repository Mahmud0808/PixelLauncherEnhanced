package com.drdisagree.pixellauncherenhanced.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.VIBRATE_UI
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs

object HapticUtils {

    enum class VibrationType {
        Weak,
        Strong
    }

    private fun View.vibrate(type: VibrationType) {
        if (RPrefs.getBoolean(VIBRATE_UI, true)) {
            when (type) {
                VibrationType.Weak -> performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                VibrationType.Strong -> performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    fun View.weakVibrate() {
        vibrate(VibrationType.Weak)
    }

    fun View.strongVibrate() {
        vibrate(VibrationType.Strong)
    }

    fun View.toggleVibrate(checked: Boolean) {
        if (!RPrefs.getBoolean(VIBRATE_UI, true)) return
        performHapticFeedback(
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.CLOCK_TICK
                checked -> HapticFeedbackConstants.TOGGLE_ON
                else -> HapticFeedbackConstants.TOGGLE_OFF
            }
        )
    }

    fun View.tickVibrate() {
        if (!RPrefs.getBoolean(VIBRATE_UI, true)) return
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
        )
    }
}