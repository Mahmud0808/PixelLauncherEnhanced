package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.content.res.XResources
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge.hookAllConstructors
import de.robv.android.xposed.XposedBridge.hookAllMethods
import de.robv.android.xposed.XposedBridge.hookMethod
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findAndHookConstructor
import de.robv.android.xposed.XposedHelpers.findAndHookMethod
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import de.robv.android.xposed.callbacks.XC_LayoutInflated
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class XposedHook {
    companion object {
        lateinit var loadPackageParam: XC_LoadPackage.LoadPackageParam

        fun init(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
            Companion.loadPackageParam =
                loadPackageParam
        }

        fun findClass(
            vararg classNames: String,
            suppressError: Boolean = false,
            throwException: Boolean = false
        ): Class<*>? {
            if (Companion::loadPackageParam.isInitialized.not()) {
                throw IllegalStateException("XposedHook.init() must be called before XposedHook.findClass()")
            }

            for (className in classNames) {
                val clazz = XposedHelpers.findClassIfExists(
                    className,
                    loadPackageParam.classLoader
                )
                if (clazz != null) {
                    DebugRecorder.recordClassLookup(classNames, className, suppressError)
                    return clazz
                }
            }
            DebugRecorder.recordClassLookup(classNames, null, suppressError)

            if (throwException) {
                if (classNames.size == 1) {
                    throw Throwable("Class not found: ${classNames[0]}")
                } else {
                    throw Throwable("None of the classes were found: ${classNames.joinToString()}")
                }
            } else if (!suppressError) {
                if (classNames.size == 1) {
                    log("Class not found: ${classNames[0]}")
                } else {
                    log("None of the classes were found: ${classNames.joinToString()}")
                }
            }

            return null
        }

        fun Class<*>?.newInstance(): Any? {
            if (this == null) return null

            return XposedHelpers.newInstance(this)
        }
    }
}

fun Class<*>?.hookMethod(vararg methodNames: String): MethodHookHelper {
    return MethodHookHelper(
        this,
        methodNames
    )
}

fun Class<*>?.hookConstructor(): MethodHookHelper {
    return MethodHookHelper(this)
}

fun Class<*>?.hookMethodMatchPattern(methodNamePattern: String): MethodHookHelper {
    return MethodHookHelper(
        this,
        arrayOf(methodNamePattern),
        true
    )
}

class MethodHookHelper(
    private val clazz: Class<*>?,
    private val methodNames: Array<out String>? = null,
    private val isPattern: Boolean = false,
    private val method: Method? = null
) {

    constructor(
        clazz: Class<*>?,
        methodNames: Array<out String>? = null,
        isPattern: Boolean = false
    ) : this(
        clazz,
        methodNames,
        isPattern,
        null
    )

    constructor(
        method: Method
    ) : this(
        null,
        null,
        false,
        method
    )

    private var parameterTypes: Array<Any?>? = null
    private var printError: Boolean = true
    private var throwError: Boolean = false
    private var capability: String? = null

    fun capability(name: String): MethodHookHelper {
        capability = name
        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun parameters(vararg parameterTypes: Any?): MethodHookHelper {
        this.parameterTypes = parameterTypes as Array<Any?>?
        return this
    }

    fun run(callback: XC_MethodHook): MethodHookHelper {
        when {
            method != null -> {
                DebugRecorder.recordDirectHook(method, "raw")
                hookMethod(method, callback)
            }
            methodNames.isNullOrEmpty() -> hookConstructor(callback)
            else -> resolveMethods("raw").forEach { (method, _) -> hookMethod(method, callback) }
        }
        return this
    }

    fun runBefore(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
        when {
            method != null -> hookMethodBefore(method, DebugRecorder.recordDirectHook(method, "before"), callback)
            methodNames.isNullOrEmpty() -> hookConstructorBefore(callback)
            else -> resolveMethods("before").forEach { (method, record) -> hookMethodBefore(method, record, callback) }
        }
        return this
    }

    fun runAfter(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
        when {
            method != null -> hookMethodAfter(method, DebugRecorder.recordDirectHook(method, "after"), callback)
            methodNames.isNullOrEmpty() -> hookConstructorAfter(callback)
            else -> resolveMethods("after").forEach { (method, record) -> hookMethodAfter(method, record, callback) }
        }
        return this
    }

    fun runAround(
        before: (XC_MethodHook.MethodHookParam) -> Unit,
        after: (XC_MethodHook.MethodHookParam) -> Unit
    ): MethodHookHelper {
        fun around(record: DebugRecorder.HookRecord) = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                DebugRecorder.fired(record)
                before(param)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                after(param)
            }
        }

        when {
            method != null -> hookMethod(method, around(DebugRecorder.recordDirectHook(method, "around")))
            methodNames.isNullOrEmpty() -> {
                if (clazz == null) return this
                val record = DebugRecorder.recordHook(
                    clazz, null, "around", false, clazz.declaredConstructors.toList(), !printError, capability
                )
                if (parameterTypes.isNullOrEmpty()) {
                    hookAllConstructors(clazz, around(record))
                } else {
                    findAndHookConstructor(clazz, *parameterTypes!!, around(record))
                }
            }

            else -> resolveMethods("around").forEach { (method, record) ->
                when {
                    clazz == null -> hookMethod(method, around(record))
                    parameterTypes.isNullOrEmpty() -> hookAllMethods(clazz, method.name, around(record))
                    else -> findAndHookMethod(clazz, method.name, *parameterTypes!!, around(record))
                }
            }
        }
        return this
    }

    fun replace(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
        when {
            method != null -> hookMethodReplace(method, DebugRecorder.recordDirectHook(method, "replace"), callback)
            else -> resolveMethods("replace").forEach { (method, record) -> hookMethodReplace(method, record, callback) }
        }
        return this
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
            val errorMessage =
                "Method${if (names.size == 1) "" else "s"} not found: ${names.joinToString()} in class ${clazz?.simpleName}"

            if (printError && clazz != null) {
                log(MethodHookHelper::class.simpleName.toString(), errorMessage)
            } else if (throwError) {
                throw Throwable(errorMessage)
            }
        }

        return resolved.map { it to record }
    }

    private fun hookConstructor(callback: XC_MethodHook): MethodHookHelper {
        if (clazz == null) return this
        DebugRecorder.recordHook(clazz, null, "raw", false, clazz.declaredConstructors.toList(), !printError, capability)

        if (parameterTypes.isNullOrEmpty()) {
            hookAllConstructors(clazz, callback)
        } else {
            findAndHookConstructor(
                clazz,
                *parameterTypes!!,
                callback
            )
        }

        return this
    }

    private fun hookConstructorBefore(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
        if (clazz == null) return this
        val record = DebugRecorder.recordHook(clazz, null, "before", false, clazz.declaredConstructors.toList(), !printError, capability)

        if (parameterTypes.isNullOrEmpty()) {
            hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DebugRecorder.fired(record)
                    callback(param)
                }
            })
        } else {
            findAndHookConstructor(
                clazz,
                *parameterTypes!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        DebugRecorder.fired(record)
                        callback(param)
                    }
                }
            )
        }

        return this
    }

    private fun hookConstructorAfter(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
        if (clazz == null) return this
        val record = DebugRecorder.recordHook(clazz, null, "after", false, clazz.declaredConstructors.toList(), !printError, capability)

        if (parameterTypes.isNullOrEmpty()) {
            hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    DebugRecorder.fired(record)
                    callback(param)
                }
            })
        } else {
            findAndHookConstructor(
                clazz,
                *parameterTypes!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        DebugRecorder.fired(record)
                        callback(param)
                    }
                }
            )
        }

        return this
    }

    private fun hookMethodBefore(
        method: Method,
        record: DebugRecorder.HookRecord,
        callback: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        if (clazz == null) {
            hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DebugRecorder.fired(record)
                    callback(param)
                }
            })
        } else {
            if (parameterTypes.isNullOrEmpty()) {
                hookAllMethods(clazz, method.name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        DebugRecorder.fired(record)
                        callback(param)
                    }
                })
            } else {
                findAndHookMethod(
                    clazz,
                    method.name,
                    *parameterTypes!!,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            DebugRecorder.fired(record)
                            callback(param)
                        }
                    }
                )
            }
        }
    }

    private fun hookMethodAfter(
        method: Method,
        record: DebugRecorder.HookRecord,
        callback: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        if (clazz == null) {
            hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    DebugRecorder.fired(record)
                    callback(param)
                }
            })
        } else {
            if (parameterTypes.isNullOrEmpty()) {
                hookAllMethods(clazz, method.name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        DebugRecorder.fired(record)
                        callback(param)
                    }
                })
            } else {
                findAndHookMethod(
                    clazz,
                    method.name,
                    *parameterTypes!!,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            DebugRecorder.fired(record)
                            callback(param)
                        }
                    }
                )
            }
        }
    }

    private fun hookMethodReplace(
        method: Method,
        record: DebugRecorder.HookRecord,
        callback: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        if (clazz == null) {
            hookMethod(method, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    DebugRecorder.fired(record)
                    callback(param)
                    return null
                }
            })
        } else {
            if (parameterTypes.isNullOrEmpty()) {
                hookAllMethods(clazz, method.name, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        DebugRecorder.fired(record)
                        callback(param)
                        return null
                    }
                })
            } else {
                findAndHookMethod(
                    clazz,
                    method.name,
                    *parameterTypes!!,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            DebugRecorder.fired(record)
                            callback(param)
                            return null
                        }
                    }
                )
            }
        }
    }

    /*
     * Call before running any hook
     */
    fun suppressError(): MethodHookHelper {
        printError = false
        return this
    }

    /*
     * Call before running any hook
     */
    fun throwError(): MethodHookHelper {
        suppressError()
        throwError = true
        return this
    }
}

fun Method.run(callback: XC_MethodHook): MethodHookHelper {
    return MethodHookHelper(this)
        .run(callback)
}

fun Method.runBefore(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
    return MethodHookHelper(this)
        .runBefore(callback)
}

fun Method.runAfter(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
    return MethodHookHelper(this)
        .runAfter(callback)
}

fun Method.replace(callback: (XC_MethodHook.MethodHookParam) -> Unit): MethodHookHelper {
    return MethodHookHelper(this)
        .replace(callback)
}

fun XResources.hookLayout(): LayoutHookHelper {
    return LayoutHookHelper(this)
}

class LayoutHookHelper(private val xResources: XResources) {

    private var packageName: String? = null
    private var resourceType: String? = null
    private var resourceName: String? = null
    private var printError: Boolean = true
    private var throwError: Boolean = false

    fun packageName(packageName: String): LayoutHookHelper {
        this.packageName = packageName
        return this
    }

    fun resource(
        resourceType: String,
        resourceName: String
    ): LayoutHookHelper {
        this.resourceType = resourceType
        this.resourceName = resourceName
        return this
    }

    fun run(callback: (XC_LayoutInflated.LayoutInflatedParam) -> Unit): LayoutHookHelper {
        if (packageName == null || resourceType == null || resourceName == null) {
            throw IllegalArgumentException("packageName, resourceType and resourceName must be set")
        }

        try {
            xResources.hookLayout(
                packageName,
                resourceType,
                resourceName,
                object : XC_LayoutInflated() {
                    override fun handleLayoutInflated(param: LayoutInflatedParam) {
                        callback(param)
                    }
                }
            )
        } catch (throwable: Throwable) {
            if (printError) {
                log(LayoutHookHelper::class, throwable)
            } else if (throwError) {
                throw throwable
            }
        }

        return this
    }

    /*
     * Call before running any hook
     */
    fun suppressError(): LayoutHookHelper {
        printError = false
        return this
    }

    /*
     * Call before running any hook
     */
    fun throwError(): LayoutHookHelper {
        throwError = true
        return this
    }
}

object ResourceHookManager {

    private val hookedResources = mutableListOf<HookData>()
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context)

        applyHooks()
    }

    fun hookDimen(): HookBuilder {
        return HookBuilder(HookType.DIMENSION)
    }

    fun hookBoolean(): HookBuilder {
        return HookBuilder(HookType.BOOLEAN)
    }

    fun hookInteger(): HookBuilder {
        return HookBuilder(HookType.INTEGER)
    }

    private fun applyHooks() {
        val context = contextRef!!.get() ?: throw IllegalStateException("Context is null")

        HookType.entries.forEach { hookType ->
            hookType.methods.forEach { method ->
                Resources::class.java
                    .hookMethod(method)
                    .runBefore { param ->
                        val hookData = hookedResources.find {
                            it.method == method && it.resId == param.args[0] && it.condition.invoke()
                        } ?: return@runBefore

                        if (method == "getDimensionPixelSize") {
                            param.result = context.toPx(hookData.value.invoke() as Int)
                        } else {
                            param.result = hookData.value.invoke()
                        }
                    }
            }
        }
    }

    class HookBuilder(private val hookType: HookType) {

        private var packageName: String? = null
        private var condition: () -> Boolean = { true }
        private val resourcesToHook = mutableListOf<HookData>()

        fun whenCondition(condition: () -> Boolean): HookBuilder {
            this.condition = condition
            return this
        }

        fun forPackageName(packageName: String): HookBuilder {
            this.packageName = packageName
            return this
        }

        @SuppressLint("DiscouragedApi")
        fun addResource(name: String, value: () -> Any): HookBuilder {
            val context = contextRef?.get() ?: return this
            if (packageName == null) throw IllegalArgumentException("packageName must be set")

            val resId = context.resources.getIdentifier(
                name,
                hookType.resourceType,
                packageName
            )

            if (resId != 0) {
                hookType.methods.forEach { method ->
                    resourcesToHook.add(HookData(resId, method, value, condition))
                }
            }

            return this
        }

        fun apply() {
            resourcesToHook.forEach { resource ->
                if (!hookedResources.contains(resource)) {
                    hookedResources.add(resource)
                }
            }
        }
    }

    data class HookData(
        val resId: Int,
        val method: String,
        val value: () -> Any,
        val condition: () -> Boolean
    )

    enum class HookType(val resourceType: String, val methods: List<String>) {
        BOOLEAN(
            "bool",
            listOf("getBoolean")
        ),
        INTEGER(
            "integer",
            listOf("getInteger")
        ),
        DIMENSION(
            "dimen",
            listOf("getDimension", "getDimensionPixelOffset", "getDimensionPixelSize")
        )
    }
}

fun Any?.callMethod(methodName: String): Any? {
    if (this == null) {
        log("callMethod:noArgs", "Object is null, method=$methodName")
        return null
    }

    return XposedHelpers.callMethod(this, methodName)
}

fun Any?.callMethod(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }

    return XposedHelpers.callMethod(this, methodName, *args)
}

fun Any?.callMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
    if (this == null) {
        log("callMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }

    return XposedHelpers.callMethod(this, methodName, parameterTypes, *args)
}

fun Any?.callMethodSilently(methodName: String): Any? {
    if (this == null) {
        log("callMethodSilently:noArgs", "Object is null, method=$methodName")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }
    if (knownMiss(missingMethods, javaClass, methodName)) return null

    return try {
        XposedHelpers.callMethod(this, methodName)
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
        XposedHelpers.callMethod(this, methodName, *args)
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
        XposedHelpers.callMethod(this, methodName, parameterTypes, *args)
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

    return XposedHelpers.callStaticMethod(this, methodName)
}

fun Class<*>?.callStaticMethod(methodName: String, vararg args: Any?): Any? {
    if (this == null) {
        log("callStaticMethod:withArgs", "Object is null, method=$methodName, args=${args.size}")
        return null
    }

    return XposedHelpers.callStaticMethod(this, methodName, *args)
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

    return XposedHelpers.callStaticMethod(this, methodName, parameterTypes, *args)
}

fun Class<*>?.callStaticMethodSilently(methodName: String): Any? {
    if (this == null) {
        log("callStaticMethodSilently:noArgs", "Object is null, method=$methodName")
        DebugRecorder.recordMemberMiss("null-receiver", null, methodName)
        return null
    }
    if (knownMiss(missingMethods, this, methodName)) return null

    return try {
        XposedHelpers.callStaticMethod(this, methodName)
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
        XposedHelpers.callStaticMethod(this, methodName, *args)
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
        XposedHelpers.callStaticMethod(this, methodName, parameterTypes, *args)
    } catch (throwable: Throwable) {
        DebugRecorder.recordMemberMiss(throwable.missKind("static-method"), this, methodName)
        null
    }
}

fun Any?.getField(fieldName: String): Any {
    if (this == null) throw NoSuchFieldError("Field not found: $fieldName, object is null")

    return XposedHelpers.getObjectField(this, fieldName)
}

fun Any?.getFieldSilently(fieldName: String): Any? {
    if (this == null) return null
    if (knownMiss(missingFields, javaClass, fieldName)) return null

    return try {
        XposedHelpers.getObjectField(this, fieldName)
    } catch (throwable: Throwable) {
        rememberMiss(missingFields, javaClass, fieldName, "field", this, throwable)
        null
    }
}

fun Any?.setField(fieldName: String, value: Any?) {
    XposedHelpers.setObjectField(this, fieldName, value)
}

fun Any?.setFieldSilently(fieldName: String, value: Any?): Boolean {
    if (this == null) {
        DebugRecorder.recordMemberMiss("null-receiver", null, fieldName)
        return false
    }
    if (knownMiss(missingFields, javaClass, fieldName)) return false

    return try {
        XposedHelpers.setObjectField(this, fieldName, value)
        true
    } catch (throwable: Throwable) {
        rememberMiss(missingFields, javaClass, fieldName, "set-field", this, throwable)
        false
    }
}

fun Class<*>?.getStaticField(fieldName: String): Any {
    if (this == null) throw NoSuchFieldError("Field not found: $fieldName, class is null")

    return getStaticObjectField(this, fieldName)
}

fun Class<*>?.getStaticFieldSilently(fieldName: String): Any? {
    if (this == null) return null
    if (knownMiss(missingFields, this, fieldName)) return null

    return try {
        getStaticObjectField(this, fieldName)
    } catch (throwable: Throwable) {
        rememberMiss(missingFields, this, fieldName, "static-field", this, throwable)
        null
    }
}

fun Class<*>?.setStaticField(fieldName: String, value: Any?) {
    XposedHelpers.setStaticObjectField(this, fieldName, value)
}

fun Class<*>?.setStaticFieldSilently(fieldName: String, value: Any?) {
    if (this == null) return
    if (knownMiss(missingFields, this, fieldName)) return

    try {
        XposedHelpers.setStaticObjectField(this, fieldName, value)
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
            return FieldValue(XposedHelpers.getObjectField(this, fieldName)).also {
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
            return XposedHelpers.setObjectField(this, fieldName, value)
        } catch (_: Throwable) {
        }
    }

    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

fun Class<*>?.getAnyStaticField(vararg fieldNames: String): Any? {
    fieldNames.forEach { fieldName ->
        try {
            return getStaticObjectField(this, fieldName)
        } catch (_: Throwable) {
        }
    }

    throw NoSuchFieldError("Field not found: ${fieldNames.joinToString()}")
}

private class IdentityKey(
    referent: Any,
    queue: ReferenceQueue<Any>?
) : WeakReference<Any>(referent, queue) {
    private val hash = System.identityHashCode(referent)

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityKey || other.hash != hash) return false
        val referent = get()
        return referent != null && referent === other.get()
    }
}

private val extraFieldsQueue = ReferenceQueue<Any>()
private val extraFields = HashMap<IdentityKey, HashMap<String, Any?>>()

private fun Any.extraFields(create: Boolean): HashMap<String, Any?>? = synchronized(extraFields) {
    while (true) {
        val stale = extraFieldsQueue.poll() ?: break
        extraFields.remove(stale)
    }

    val key = IdentityKey(this, null)
    extraFields[key] ?: if (create) {
        HashMap<String, Any?>().also { extraFields[IdentityKey(this, extraFieldsQueue)] = it }
    } else {
        null
    }
}

fun Any?.getExtraField(fieldName: String): Any {
    val fields = requireNotNull(this).extraFields(false)
    return fields?.get(fieldName) ?: throw NoSuchFieldError("Extra field not found: $fieldName")
}

fun Any?.getExtraFieldSilently(fieldName: String): Any? {
    return this?.extraFields(false)?.get(fieldName)
}

fun Any?.setExtraField(fieldName: String, value: Any?) {
    val fields = requireNotNull(this).extraFields(true) ?: return
    synchronized(extraFields) { fields[fieldName] = value }
}

private fun Throwable.missKind(base: String): String = when (this) {
    is NoSuchFieldError, is NoSuchMethodError -> base
    is XposedHelpers.InvocationTargetError -> "$base-threw:${cause?.javaClass?.simpleName}"
    else -> "$base-failed:${javaClass.simpleName}"
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
