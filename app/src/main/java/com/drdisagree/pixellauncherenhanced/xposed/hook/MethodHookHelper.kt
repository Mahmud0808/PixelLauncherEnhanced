package com.drdisagree.pixellauncherenhanced.xposed.hook

import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.regex.Pattern

class MethodHookHelper internal constructor(
    private val clazz: Class<*>?,
    private val methodNames: Array<out String>? = null,
    private val isPattern: Boolean = false,
    private val executable: Executable? = null
) {

    constructor(executable: Executable) : this(null, executable = executable)

    private var parameterTypes: Array<out Any?>? = null
    private var printError = true
    private var throwError = false
    private var capability: String? = null

    fun capability(name: String): MethodHookHelper {
        capability = name
        return this
    }

    fun parameters(vararg parameterTypes: Any?): MethodHookHelper {
        this.parameterTypes = parameterTypes
        return this
    }

    fun suppressError(): MethodHookHelper {
        printError = false
        return this
    }

    fun throwError(): MethodHookHelper {
        suppressError()
        throwError = true
        return this
    }

    fun runBefore(callback: (HookParam) -> Unit): MethodHookHelper = install("before", before = callback)

    fun runAfter(callback: (HookParam) -> Unit): MethodHookHelper = install("after", after = callback)

    fun runAround(before: (HookParam) -> Unit, after: (HookParam) -> Unit): MethodHookHelper =
        install("around", before, after)

    private fun install(
        kind: String,
        before: ((HookParam) -> Unit)? = null,
        after: ((HookParam) -> Unit)? = null
    ): MethodHookHelper {
        targets(kind).forEach { (target, record) ->
            ModuleRuntime.module.hook(target).intercept(HookCallback(record, before, after))
        }
        return this
    }

    private fun targets(kind: String): List<Pair<Executable, DebugRecorder.HookRecord>> = when {
        executable != null -> listOf(executable to DebugRecorder.recordDirectHook(executable, kind))
        methodNames.isNullOrEmpty() -> constructorTargets(kind)
        else -> methodTargets(kind)
    }

    private fun constructorTargets(kind: String): List<Pair<Executable, DebugRecorder.HookRecord>> {
        val type = clazz ?: return emptyList()
        val record = DebugRecorder.recordHook(
            type, null, kind, false, type.declaredConstructors.toList(), !printError, capability
        )
        val constructors = if (parameterTypes.isNullOrEmpty()) {
            type.declaredConstructors.toList()
        } else {
            listOf(Reflection.findConstructorExact(type, *parameterTypes.orEmpty()))
        }
        return constructors.map { it to record }
    }

    private fun methodTargets(kind: String): List<Pair<Executable, DebugRecorder.HookRecord>> {
        return resolveMethods(kind).flatMap { (method, record) ->
            val type = clazz
            val methods = when {
                type == null -> listOf(method)
                parameterTypes.isNullOrEmpty() -> type.declaredMethods.filter {
                    it.name == method.name && !Modifier.isAbstract(it.modifiers)
                }

                else -> listOf(Reflection.findMethodExact(type, method.name, *parameterTypes.orEmpty()))
            }
            methods.map { it to record }
        }
    }

    private fun resolveMethods(kind: String): List<Pair<Method, DebugRecorder.HookRecord>> {
        val names = methodNames ?: return emptyList()
        val candidates = clazz?.declaredMethods?.toList()?.union(clazz.methods.toList()).orEmpty()
        val resolved = names.flatMap { methodName ->
            if (isPattern) {
                val pattern = Pattern.compile(methodName)
                candidates.filter { pattern.matcher(it.name).matches() && !Modifier.isAbstract(it.modifiers) }
            } else {
                listOfNotNull(candidates.find { it.name == methodName && !Modifier.isAbstract(it.modifiers) })
            }
        }
        val record = DebugRecorder.recordHook(clazz, names, kind, isPattern, resolved, !printError, capability)

        if (resolved.isEmpty()) {
            val message = "Method${if (names.size == 1) "" else "s"} not found: ${names.joinToString()} in class ${clazz?.simpleName}"
            if (printError && clazz != null) {
                log(TAG, message)
            } else if (throwError) {
                throw NoSuchMethodError(message)
            }
        }

        return resolved.map { it to record }
    }

    private companion object {
        const val TAG = "MethodHookHelper"
    }
}
