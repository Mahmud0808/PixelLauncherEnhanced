package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.util.ArraySet
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

private val methodPresence = ConcurrentHashMap<List<Any?>, Boolean>()

fun Any?.hasMethod(methodName: String, vararg parameterTypes: Class<*>?): Boolean {
    if (this == null) return false
    val type = this as? Class<*> ?: this::class.java
    val key = listOf(type, methodName, *parameterTypes)
    return methodPresence.getOrPut(key) { type.findsMethod(methodName, parameterTypes) }
}

private fun Class<*>.findsMethod(methodName: String, parameterTypes: Array<out Class<*>?>): Boolean {
    if (parameterTypes.isEmpty()) {
        return declaredMethods.any { it.name == methodName } || methods.any { it.name == methodName }
    }
    return try {
        getDeclaredMethod(methodName, *parameterTypes)
        true
    } catch (_: NoSuchMethodException) {
        try {
            getMethod(methodName, *parameterTypes)
            true
        } catch (_: NoSuchMethodException) {
            false
        }
    }
}

fun Class<*>?.isFieldAvailable(fieldName: String): Boolean {
    if (this == null) return false

    return try {
        this::class.java.getDeclaredField(fieldName)
        true
    } catch (_: NoSuchFieldException) {
        false
    }
}

fun Class<*>?.findMethod(namePattern: String): Method? {
    if (this == null) return null

    val methods: Array<Method> = declaredMethods.toList().union(methods.toList()).toTypedArray()

    for (method in methods) {
        if (Pattern.matches(namePattern, method.name)) {
            return method
        }
    }

    return null
}

fun Class<*>?.findMethods(namePattern: String): Set<Method> {
    if (this == null) return emptySet()

    val result: MutableSet<Method> = ArraySet()
    val methods: Array<Method> = declaredMethods.toList().union(methods.toList()).toTypedArray()

    methods.forEach { method ->
        if (Pattern.matches(namePattern, method.name)) {
            result.add(method)
        }
    }

    return result
}