package com.drdisagree.pixellauncherenhanced.data.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

class PackIconDrawable(context: Context, bitmap: Bitmap) : BitmapDrawable(context.resources, bitmap) {

    companion object {
        fun wrap(context: Context, drawable: Drawable): Drawable {
            if (drawable is PackIconDrawable || drawable is android.graphics.drawable.AdaptiveIconDrawable) return drawable

            val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
                val size = drawable.intrinsicWidth.takeIf { it > 0 }
                    ?: (context.resources.displayMetrics.density * 96).toInt()
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(bitmap))
                }
            }

            return PackIconDrawable(context, bitmap)
        }
    }
}
