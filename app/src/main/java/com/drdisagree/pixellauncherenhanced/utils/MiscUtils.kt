package com.drdisagree.pixellauncherenhanced.utils

import android.util.TypedValue
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.drdisagree.pixellauncherenhanced.PLEnhanced.Companion.appContext
import com.drdisagree.pixellauncherenhanced.R
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar

object MiscUtils {

    fun dpToPx(dp: Int): Int {
        return dpToPx(dp.toFloat())
    }

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            appContext.resources.displayMetrics
        ).toInt()
    }

    fun setupToolbar(
        baseContext: AppCompatActivity,
        @StringRes title: Int,
        showBackButton: Boolean,
        toolbar: MaterialToolbar?,
        collapsingToolbarLayout: CollapsingToolbarLayout?
    ) {
        setupToolbar(
            baseContext,
            baseContext.getString(title),
            showBackButton,
            toolbar,
            collapsingToolbarLayout
        )
    }

    fun setupToolbar(
        baseContext: AppCompatActivity,
        title: String,
        showBackButton: Boolean,
        toolbar: MaterialToolbar?,
        collapsingToolbarLayout: CollapsingToolbarLayout?
    ) {
        toolbar?.let { baseContext.setSupportActionBar(it) }

        baseContext.supportActionBar?.apply {
            setTitle(title)
            setDisplayHomeAsUpEnabled(showBackButton)
            setHomeAsUpIndicator(R.drawable.ic_toolbar_chevron)
        }

        collapsingToolbarLayout?.title = title
    }
}