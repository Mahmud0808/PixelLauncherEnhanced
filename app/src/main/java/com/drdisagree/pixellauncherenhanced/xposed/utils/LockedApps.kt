package com.drdisagree.pixellauncherenhanced.xposed.utils

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

object LockedApps {

    private const val PREFS_NAME = "ple_locked_apps"
    private const val KEY_LOCKED = "locked"

    @Volatile
    private var cache: Set<String>? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun isLocked(context: Context, packageName: String, userId: Int): Boolean {
        return load(context).contains(key(packageName, userId))
    }

    @Synchronized
    fun setLocked(context: Context, packageName: String, userId: Int, locked: Boolean) {
        val updated = load(context).toMutableSet().apply {
            if (locked) add(key(packageName, userId)) else remove(key(packageName, userId))
        }
        cache = updated
        prefs(context).edit().putStringSet(KEY_LOCKED, updated).apply()
        listeners.forEach { it() }
    }

    private fun load(context: Context): Set<String> {
        return cache ?: prefs(context).getStringSet(KEY_LOCKED, emptySet()).orEmpty().toSet().also { cache = it }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(packageName: String, userId: Int) = "$packageName:$userId"
}
