package com.drdisagree.pixellauncherenhanced.utils

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACK_APPLIED
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs

object IconApplyDialog {

    private const val TIMEOUT_MS = 30_000L
    private const val SETTLE_MS = 1_200L

    fun apply(fragment: Fragment, onDone: () -> Unit = {}) {
        val token = IconPackStore.applyIfChanged() ?: return
        val context = fragment.requireContext()
        val handler = Handler(Looper.getMainLooper())

        val dialog = ExpressiveDialog.show(
            context = context,
            icon = R.drawable.ic_icons,
            title = context.getString(R.string.icon_apply_title),
            message = context.getString(R.string.icon_apply_desc),
            cancelable = false,
            loading = true
        )

        var finished = false
        lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener

        fun finish(applied: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            RPrefs.getPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            if (dialog.isShowing) runCatching { dialog.dismiss() }
            if (applied) Messages.show(context, context.getString(R.string.icon_apply_done))
            onDone()
        }

        listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == ICON_PACK_APPLIED && prefs.getLong(key, 0L) == token) {
                handler.postDelayed({ finish(true) }, SETTLE_MS)
            }
        }

        RPrefs.getPrefs.registerOnSharedPreferenceChangeListener(listener)
        handler.postDelayed({ finish(false) }, TIMEOUT_MS)
    }
}
