package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.annotation.SuppressLint
import android.content.res.Resources
import java.util.concurrent.ConcurrentHashMap

object ResourceIds {

    private val cache = ConcurrentHashMap<String, Int>()
    private val entryNames = ConcurrentHashMap<Int, String>()

    @SuppressLint("DiscouragedApi")
    fun find(resources: Resources, name: String, type: String, packageName: String): Int {
        return cache.getOrPut("$packageName:$type/$name") { resources.getIdentifier(name, type, packageName) }
    }

    fun entryName(resources: Resources, id: Int): String? {
        if (id == 0 || id == -1 || id ushr 24 == 0) return null
        val name = entryNames.getOrPut(id) {
            runCatching { resources.getResourceEntryName(id) }.getOrNull() ?: MISSING
        }
        return name.takeIf { it.isNotEmpty() }
    }

    private const val MISSING = ""
}
