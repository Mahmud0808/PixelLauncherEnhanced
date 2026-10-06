package com.drdisagree.pixellauncherenhanced.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.Snackbar

object Messages {

    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        val root = context.findActivity()?.findViewById<View>(android.R.id.content)

        if (root != null && root.isAttachedToWindow) {
            val anchor = root.findCoordinator() ?: root
            Snackbar.make(anchor, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).apply {
                view.setOnClickListener { dismiss() }
                show()
            }
        } else {
            Toast.makeText(context.applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    private fun View.findCoordinator(): CoordinatorLayout? {
        val pending = ArrayDeque<View>().apply { add(this@findCoordinator) }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is CoordinatorLayout && view.isShown) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
        }
        return null
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
