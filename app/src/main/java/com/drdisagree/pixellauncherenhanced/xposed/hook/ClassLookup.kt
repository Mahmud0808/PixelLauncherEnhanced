package com.drdisagree.pixellauncherenhanced.xposed.hook

import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder

fun findClass(
    vararg classNames: String,
    suppressError: Boolean = false,
    throwException: Boolean = false
): Class<*>? {
    check(ModuleRuntime.isPackageLoaded) { "findClass() called before the package was loaded" }
    val classLoader = ModuleRuntime.loadedPackage.classLoader

    classNames.forEach { className ->
        Reflection.findClassIfExists(className, classLoader)?.let { clazz ->
            DebugRecorder.recordClassLookup(classNames, className, suppressError)
            return clazz
        }
    }
    DebugRecorder.recordClassLookup(classNames, null, suppressError)

    val message = if (classNames.size == 1) {
        "Class not found: ${classNames[0]}"
    } else {
        "None of the classes were found: ${classNames.joinToString()}"
    }
    if (throwException) throw ClassNotFoundException(message)
    if (!suppressError) log(message)
    return null
}

fun Class<*>?.createInstance(vararg args: Any?): Any? = this?.let { Reflection.newInstance(it, *args) }
