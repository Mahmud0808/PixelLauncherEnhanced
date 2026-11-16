package com.drdisagree.pixellauncherenhanced.xposed.hook

import java.lang.reflect.Executable

fun Class<*>?.hookMethod(vararg methodNames: String): MethodHookHelper = MethodHookHelper(this, methodNames)

fun Class<*>?.hookConstructor(): MethodHookHelper = MethodHookHelper(this)

fun Class<*>?.hookMethodMatchPattern(methodNamePattern: String): MethodHookHelper =
    MethodHookHelper(this, arrayOf(methodNamePattern), isPattern = true)

fun Executable.runBefore(callback: (HookParam) -> Unit): MethodHookHelper =
    MethodHookHelper(this).runBefore(callback)

fun Executable.runAfter(callback: (HookParam) -> Unit): MethodHookHelper =
    MethodHookHelper(this).runAfter(callback)
