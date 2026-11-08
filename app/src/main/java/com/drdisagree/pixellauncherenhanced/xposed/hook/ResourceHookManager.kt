package com.drdisagree.pixellauncherenhanced.xposed.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.util.TypedValue
import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ResourceHookManager {

    private val hookedResources = ConcurrentHashMap<Int, CopyOnWriteArrayList<HookData>>()
    private var contextRef: WeakReference<Context>? = null

    @Volatile
    private var valueHooksInstalled = false

    @Volatile
    private var colorHooksInstalled = false

    fun init(context: Context) {
        contextRef = WeakReference(context)
    }

    fun hookDimen(): HookBuilder = HookBuilder(HookType.DIMENSION)

    fun hookBoolean(): HookBuilder = HookBuilder(HookType.BOOLEAN)

    fun hookInteger(): HookBuilder = HookBuilder(HookType.INTEGER)

    fun hookColor(): HookBuilder = HookBuilder(HookType.COLOR)

    private fun activeValue(resId: Int, type: HookType): HookData? =
        hookedResources[resId]?.firstOrNull { it.type == type && it.condition() }

    @Synchronized
    private fun ensureValueHooks() {
        if (valueHooksInstalled) return
        valueHooksInstalled = true

        listOf(HookType.BOOLEAN, HookType.INTEGER, HookType.DIMENSION).forEach { type ->
            type.methods.forEach { method ->
                Resources::class.java
                    .hookMethod(method)
                    .runBefore { param ->
                        val hookData = activeValue(param.args[0] as? Int ?: return@runBefore, type) ?: return@runBefore
                        val context = contextRef?.get() ?: return@runBefore
                        param.result = if (method == "getDimensionPixelSize") {
                            context.toPx(hookData.value() as Int)
                        } else {
                            hookData.value()
                        }
                    }
            }
        }
    }

    @Synchronized
    private fun ensureColorHooks() {
        if (colorHooksInstalled) return
        colorHooksInstalled = true

        val module = ModuleRuntime.module
        val resourcesImpl = Reflection.findClass("android.content.res.ResourcesImpl", null)
        val getValue = Reflection.findMethodExact(
            resourcesImpl, "getValue",
            Int::class.javaPrimitiveType!!, TypedValue::class.java, Boolean::class.javaPrimitiveType!!
        )
        module.hook(getValue).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val color = colorFor(chain.getArg(0) as Int)
            if (color != null) {
                (chain.getArg(1) as TypedValue).apply {
                    type = TypedValue.TYPE_INT_COLOR_ARGB8
                    data = color
                    string = null
                }
            }
            result
        })

        val getColor = Reflection.findMethodExact(
            TypedArray::class.java, "getColor", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        )
        module.hook(getColor).intercept(XposedInterface.Hooker { chain ->
            val array = chain.thisObject as TypedArray
            colorFor(array.getResourceId(chain.getArg(0) as Int, 0)) ?: chain.proceed()
        })

        val getColorStateList = Reflection.findMethodExact(
            TypedArray::class.java, "getColorStateList", Int::class.javaPrimitiveType!!
        )
        module.hook(getColorStateList).intercept(XposedInterface.Hooker { chain ->
            val array = chain.thisObject as TypedArray
            colorFor(array.getResourceId(chain.getArg(0) as Int, 0))?.let { ColorStateList.valueOf(it) } ?: chain.proceed()
        })
    }

    private fun colorFor(resId: Int): Int? {
        if (resId == 0) return null
        return activeValue(resId, HookType.COLOR)?.value?.invoke() as? Int
    }

    class HookBuilder internal constructor(private val hookType: HookType) {

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
            val pkg = requireNotNull(packageName) { "packageName must be set" }
            val resId = context.resources.getIdentifier(name, hookType.resourceType, pkg)
            if (resId != 0) resourcesToHook.add(HookData(resId, hookType, value, condition))
            return this
        }

        fun apply() {
            if (resourcesToHook.isEmpty()) return
            resourcesToHook.forEach { data ->
                val list = hookedResources.getOrPut(data.resId) { CopyOnWriteArrayList() }
                if (list.none { it.type == data.type && it.value === data.value }) list.add(data)
            }
            if (hookType == HookType.COLOR) ensureColorHooks() else ensureValueHooks()
        }
    }

    class HookData internal constructor(
        val resId: Int,
        val type: HookType,
        val value: () -> Any,
        val condition: () -> Boolean
    )

    enum class HookType(val resourceType: String, val methods: List<String>) {
        BOOLEAN("bool", listOf("getBoolean")),
        INTEGER("integer", listOf("getInteger")),
        DIMENSION("dimen", listOf("getDimension", "getDimensionPixelOffset", "getDimensionPixelSize")),
        COLOR("color", emptyList())
    }
}
