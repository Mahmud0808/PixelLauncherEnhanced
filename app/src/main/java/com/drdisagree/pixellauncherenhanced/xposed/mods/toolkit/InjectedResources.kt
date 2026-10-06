package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit

import android.annotation.SuppressLint
import android.content.res.Resources
import android.util.TypedValue
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import java.util.concurrent.ConcurrentHashMap

object InjectedResources {

    private val modResIds = ConcurrentHashMap<Int, Int>()

    @Volatile
    private var hooked = false

    fun idFor(modResId: Int): Int {
        ensureHooked()
        val fakeId = 0x7D000000 or (modResId and 0x00FFFFFF)
        modResIds[fakeId] = modResId
        return fakeId
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    @Synchronized
    private fun ensureHooked() {
        if (hooked) return
        hooked = true

        Resources::class.java
            .hookMethod("getText")
            .parameters(Int::class.javaPrimitiveType)
            .runBefore { param ->
                val modResId = modResIds[param.args[0] as Int] ?: return@runBefore
                param.result = modRes.getText(modResId)
            }

        Resources::class.java
            .hookMethod("getDrawable")
            .parameters(Int::class.javaPrimitiveType, Resources.Theme::class.java)
            .runBefore { param ->
                val modResId = modResIds[param.args[0] as Int] ?: return@runBefore
                param.result = modRes.getDrawable(modResId, null)
            }

        Resources::class.java
            .hookMethod("getValue")
            .parameters(Int::class.javaPrimitiveType, TypedValue::class.java, Boolean::class.javaPrimitiveType)
            .runBefore { param ->
                val modResId = modResIds[param.args[0] as Int] ?: return@runBefore
                modRes.getValue(modResId, param.args[1] as TypedValue, param.args[2] as Boolean)
                param.result = null
            }

        Resources::class.java
            .hookMethod("getXml")
            .parameters(Int::class.javaPrimitiveType)
            .runBefore { param ->
                val modResId = modResIds[param.args[0] as Int] ?: return@runBefore
                param.result = modRes.getXml(modResId)
            }
    }
}
