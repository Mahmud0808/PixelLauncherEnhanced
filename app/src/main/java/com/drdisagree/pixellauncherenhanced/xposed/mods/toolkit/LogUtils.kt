package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import android.util.Log
import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callMethod

fun log(message: String?) {
    writeLog(message)
}

fun log(message: Any?) {
    writeLog(message.toString())
}

fun log(tag: String, message: Any?) {
    writeLog(
        "[PLEnhanced] $tag => $message"
    )
}

fun <T : Any> log(clazz: T, message: Any?) {
    writeLog(
        "[PLEnhanced] ${clazz.javaClass.simpleName.replace("\$Companion", "")} => $message"
    )
}

fun <T : Any> log(clazz: T, throwable: Throwable?) {
    DebugRecorder.recordError(clazz.javaClass.simpleName.replace("\$Companion", ""), null, throwable)
    writeLog(
        "[PLEnhanced] ${clazz.javaClass.simpleName.replace("\$Companion", "")} => $throwable"
    )
}

fun <T : Any> log(clazz: T, exception: Exception?) {
    DebugRecorder.recordError(clazz.javaClass.simpleName.replace("\$Companion", ""), null, exception)
    writeLog(
        "[PLEnhanced] ${clazz.javaClass.simpleName.replace("\$Companion", "")} => $exception"
    )
}

private const val TAG = "PLEnhanced"

private fun writeLog(message: String?) {
    if (ModuleRuntime.isAttached) {
        ModuleRuntime.module.log(Log.INFO, TAG, message.toString())
    } else {
        Log.i(TAG, message.toString())
    }
}

fun dumpClass(className: String, classLoader: ClassLoader?) {
    Reflection.findClassIfExists(className, classLoader).dumpClass()
}

fun Class<*>?.dumpClass() {
    if (this == null) {
        writeLog("DumpClass: Class is null")
        return
    }

    writeLog("\n\nClass: $name")
    writeLog("extends: ${superclass?.name}")

    writeLog("Subclasses:")
    val scs = classes
    for (c in scs) {
        writeLog("\t" + c.name)
    }
    if (scs.isEmpty()) {
        writeLog("\tNone")
    }

    writeLog("Constructors:")
    val cons = declaredConstructors
    for (m in cons) {
        writeLog("\t" + m.name + " - " + this::class.java.simpleName + " - " + m.parameterCount)
        val cs = m.parameterTypes
        for (c in cs) {
            writeLog("\t\t" + c.typeName)
        }
    }
    if (cons.isEmpty()) {
        writeLog("\tNone")
    }

    writeLog("Methods:")
    val ms = declaredMethods.toList().union(methods.toList())
    for (m in ms) {
        writeLog("\t" + m.name + " - " + m.returnType + " - " + m.parameterCount)
        val cs = m.parameterTypes
        for (c in cs) {
            writeLog("\t\t" + c.typeName)
        }
    }
    if (ms.isEmpty()) {
        writeLog("\tNone")
    }

    writeLog("Fields:")
    val fs = declaredFields
    for (f in fs) {
        writeLog("\t" + f.name + " - " + f.type.name)
    }
    if (fs.isEmpty()) {
        writeLog("\tNone")
    }
    writeLog("End dump\n\n")
}

fun View.dumpChildViews() {
    if (this is ViewGroup) {
        logViewInfo(this, 0)
        dumpChildViewsRecursive(this, 0)
    } else {
        logViewInfo(this, 0)
    }
}

private fun dumpChildViewsRecursive(
    viewGroup: ViewGroup,
    indentationLevel: Int
) {
    for (i in 0 until viewGroup.childCount) {
        val childView = viewGroup.getChildAt(i)
        logViewInfo(childView, indentationLevel + 1)
        if (childView is ViewGroup) {
            dumpChildViewsRecursive(childView, indentationLevel + 1)
        }
    }
}

private fun logViewInfo(view: View, indentationLevel: Int) {
    val indentation = repeatString("\t", indentationLevel)
    val viewName = view.javaClass.simpleName
    val superclassName = view.javaClass.superclass?.simpleName ?: "None"
    val backgroundDrawable = view.background
    val childCount = if (view is ViewGroup) view.childCount else 0
    var resourceIdName = "none"
    try {
        val viewId = view.id
        resourceIdName = view.context.resources.getResourceName(viewId)
    } catch (_: Throwable) {
    }
    var logMessage = "$indentation$viewName (Extends: $superclassName) - ID: $resourceIdName"
    if (childCount > 0) {
        logMessage += " - ChildCount: $childCount"
    }
    if (backgroundDrawable != null) {
        logMessage += " - Background: ${backgroundDrawable.javaClass.simpleName}"
    }
    writeLog(logMessage)
}

@Suppress("SameParameterValue")
private fun repeatString(str: String, times: Int): String {
    val result = StringBuilder()
    for (i in 0 until times) {
        result.append(str)
    }
    return result.toString()
}

fun Any.dumpPreferenceKeys() {
    for (i in 0 until callMethod("getPreferenceCount") as Int) {
        val preference = callMethod("getPreference", i)!!

        log("${preference::class.java.simpleName} -> Key: ${preference.callMethod("getKey")}")

        if (preference::class.java.simpleName == "PreferenceCategory") {
            preference.dumpPreferenceKeys()
        }
    }
}