package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.graphics.Rect

fun Any?.folderPreviewBounds(out: Rect): Boolean {
    if (this == null) return false
    val size = getFieldSilently("previewSize") as? Int
    val left = getFieldSilently("basePreviewOffsetX") as? Int
    val top = getFieldSilently("basePreviewOffsetY") as? Int
    if (size != null && left != null && top != null) {
        out.set(left, top, left + size, top + size)
    } else {
        out.set(getFieldSilently("mBackgroundBounds") as? Rect ?: return false)
    }
    return !out.isEmpty
}
