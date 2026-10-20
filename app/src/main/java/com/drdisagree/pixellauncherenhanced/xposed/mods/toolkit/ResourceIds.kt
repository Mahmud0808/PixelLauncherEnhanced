package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.annotation.SuppressLint
import android.content.res.Resources
import java.util.concurrent.ConcurrentHashMap

object ResourceIds {

    private val cache = ConcurrentHashMap<String, Int>()

    @SuppressLint("DiscouragedApi")
    fun find(resources: Resources, name: String, type: String, packageName: String): Int {
        return cache.getOrPut("$packageName:$type/$name") { resources.getIdentifier(name, type, packageName) }
    }
}
