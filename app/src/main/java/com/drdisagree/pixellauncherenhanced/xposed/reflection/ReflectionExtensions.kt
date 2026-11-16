package com.drdisagree.pixellauncherenhanced.xposed.reflection

import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun Any?.callMethod(methodName: String): Any? {
    if (this == null) {
        log("callMethod:noArgs", "Object is null, method=$methodName")
        return null
    }

    return Reflection.callMethod(this, methodName)
}

fun Any?.callMethod(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }

    return Reflection.callMethod(this, methodName, *args)
}

fun Any?.callMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
    if (this == null) {
        log("callMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }

    return Reflection.callMethod(this, methodName, parameterTypes, *args)
}

fun Any?.callMethodSilently(methodName: String): Any? {
    if (this == null) {
        log("callMethodSilently:noArgs", "Object is null, method=$methodName")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }
    if (knownMiss(missingMethods, javaClass, methodName)) return null

    return try {
        Reflection.callMethod(this, methodName)
    } catch (throwable: Throwable) {
        rememberMiss(missingMethods, javaClass, methodName, "method", this, throwable)
        null
    }
}

fun Any?.callMethodSilently(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callMethodSilently:withArgs", "Object is null, method=$methodName, args=${args.size}")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }

    return try {
        Reflection.callMethod(this, methodName, *args)
    } catch (throwable: Throwable) {
        DebugRecorder.recordMemberMiss(throwable.missKind("method"), this, methodName)
        null
    }
}

fun Any?.callMethodSilently(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? {
    if (this == null) {
        log("callMethodSilently:withArgs", "Object is null, method=$methodName, args=${args.size}")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }

    return try {
        Reflection.callMethod(this, methodName, parameterTypes, *args)
    } catch (throwable: Throwable) {
        DebugRecorder.recordMemberMiss(throwable.missKind("method"), this, methodName)
        null
    }
}

fun Class<*>?.callStaticMethod(methodName: String): Any? {
    if (this == null) {
        log("callStaticMethod:noArgs", "Object is null, method=$methodName")
        return null
    }

    return Reflection.callStaticMethod(requireNotNull(this), methodName)
}

fun Class<*>?.callStaticMethod(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callStaticMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }

    return Reflection.callStaticMethod(requireNotNull(this), methodName, *args)
}

fun Class<*>?.callStaticMethod(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? {
    if (this == null) {
        log("callStaticMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }

    return Reflection.callStaticMethod(requireNotNull(this), methodName, parameterTypes, *args)
}

fun Class<*>?.callStaticMethodSilently(methodName: String): Any? {
    if (this == null) {
        log("callStaticMethodSilently:noArgs", "Object is null, method=$methodName")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }
    if (knownMiss(missingMethods, this, methodName)) return null

    return try {
        Reflection.callStaticMethod(requireNotNull(this), methodName)
    } catch (throwable: Throwable) {
        rememberMiss(missingMethods, this, methodName, "static-method", this, throwable)
        null
    }
}

fun Class<*>?.callStaticMethodSilently(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callStaticMethodSilently:withArgs", "Object is null, method=$methodName, args=${args.size}")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }

    return try {
        Reflection.callStaticMethod(requireNotNull(this), methodName, *args)
    } catch (throwable: Throwable) {
        DebugRecorder.recordMemberMiss(throwable.missKind("static-method"), this, methodName)
        null
    }
}

fun Class<*>?.callStaticMethodSilently(
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? {
    if (this == null) {
        log("callStaticMethodSilently:withArgs", "Object is null, method=$methodName, args=${args.size}")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }

    return try {
        Reflection.callStaticMethod(requireNotNull(this), methodName, parameterTypes, *args)
    } catch (throwable: Throwable) {
        DebugRecorder.recordMemberMiss(throwable.missKind("static-method"), this, methodName)
        null
    }
}

fun Any?.getField(fieldName: String): Any {
    if (this == null) throw NoSuchFieldError("Field not found: $fieldName, object is null")

    return Reflection.getObjectField(this, fieldName)!!
}

fun Any?.getFieldSilently(fieldName: String): Any? {
    if (this == null) return null
    if (knownMiss(missingFields, javaClass, fieldName)) return null

    return try {
        Reflection.getObjectField(this, fieldName)
    } catch (throwable: Throwable) {
        rememberMiss(missingFields, javaClass, fieldName, "field", this, throwable)
        null
    }
}

fun Any?.setField(fieldName: String, value: Any?) {
    Reflection.setObjectField(this, fieldName, value)
}

fun Any?.setFieldSilently(fieldName: String, value: Any?): Boolean {
    if (this == null) {
        DebugRecorder.recordMemberMiss("null-receiver", null, fieldName)
        return false
    }
    if (knownMiss(missingFields, javaClass, fieldName)) return false

    return try {
        Reflection.setObjectField(this, fieldName, value)
        true
    } catch (throwable: Throwable) {
        rememberMiss(missingFields, javaClass, fieldName, "set-field", this, throwable)
        false
    }
}

fun Class<*>?.getStaticField(fieldName: String): Any {
    if (this == null) throw NoSuchFieldError("Field not found: $fieldName, class is null")

    return Reflection.getStaticObjectField(this, fieldName)!!
}

fun Class<*>?.getStaticFieldSilently(fieldName: String): Any? {
    if (this == null) return null
    if (knownMiss(missingFields, this, fieldName)) return null

    return try {
        Reflection.getStaticObjectField(this, fieldName)
    } catch (throwable: Throwable) {
        rememberMiss(missingFields, this, fieldName, "static-field", this, throwable)
        null
    }
}

fun Class<*>?.setStaticField(fieldName: String, value: Any?) {
    Reflection.setStaticObjectField(requireNotNull(this), fieldName, value)
}

fun Class<*>?.setStaticFieldSilently(fieldName: String, value: Any?) {
    if (this == null) return
    if (knownMiss(missingFields, this, fieldName)) return

    try {
        Reflection.setStaticObjectField(requireNotNull(this), fieldName, value)
    } catch (throwable: Throwable) {
        rememberMiss(missingFields, this, fieldName, "set-static-field", this, throwable)
    }
}

fun Any?.getAnyField(vararg fieldNames: String): Any? {
    resolveAnyField(fieldNames)?.let { return it.value }

    DebugRecorder.recordMemberMiss("field", this, *fieldNames)
    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

fun Any?.getAnyFieldSilently(vararg fieldNames: String): Any? {
    resolveAnyField(fieldNames)?.let { return it.value }

    DebugRecorder.recordMemberMiss("field", this, *fieldNames)
    return null
}

private class FieldValue(val value: Any?)

private fun Any?.resolveAnyField(fieldNames: Array<out String>): FieldValue? {
    if (this == null) return null
    val type = javaClass
    fieldNames.forEach { fieldName ->
        if (missingFields[type]?.containsKey(fieldName) == true) return@forEach
        try {
            return FieldValue(Reflection.getObjectField(this, fieldName)).also {
                DebugRecorder.recordFieldFallback(this, fieldNames, fieldName)
            }
        } catch (throwable: Throwable) {
            if (throwable is NoSuchFieldError) {
                missingFields.getOrPut(type) { ConcurrentHashMap() }.putIfAbsent(fieldName, AtomicLong())
            }
        }
    }
    return null
}

fun Any?.setAnyField(value: Any?, vararg fieldNames: String) {
    fieldNames.forEach { fieldName ->
        try {
            return Reflection.setObjectField(this, fieldName, value)
        } catch (_: Throwable) {
        }
    }

    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

fun Class<*>?.getAnyStaticField(vararg fieldNames: String): Any? {
    fieldNames.forEach { fieldName ->
        try {
            return Reflection.getStaticObjectField(requireNotNull(this@getAnyStaticField), fieldName)
        } catch (_: Throwable) {
        }
    }

    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

private fun Throwable.missKind(base: String): String = when (this) {
    is NoSuchFieldError, is NoSuchMethodError -> base
    else -> "$base-threw:${javaClass.simpleName}"
}

private val missingFields = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, AtomicLong>>()
private val missingMethods = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, AtomicLong>>()

private fun knownMiss(
    cache: ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, AtomicLong>>,
    type: Class<*>,
    name: String
): Boolean {
    val counter = cache[type]?.get(name) ?: return false
    counter.incrementAndGet()
    return true
}

private fun rememberMiss(
    cache: ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, AtomicLong>>,
    type: Class<*>,
    name: String,
    kind: String,
    target: Any?,
    throwable: Throwable
) {
    val counter = DebugRecorder.recordMemberMiss(throwable.missKind(kind), target, name)
    if (throwable is NoSuchFieldError || throwable is NoSuchMethodError) {
        cache.getOrPut(type) { ConcurrentHashMap() }[name] = counter
    }
}
