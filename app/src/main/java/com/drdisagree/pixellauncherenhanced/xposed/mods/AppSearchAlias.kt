package com.drdisagree.pixellauncherenhanced.xposed.mods

import com.drdisagree.pixellauncherenhanced.xposed.reflection.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callStaticMethod
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Process
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage
import java.util.function.Consumer

class AppSearchAlias(context: Context) : ModPack(context) {

    private val matching = ThreadLocal<Boolean>()
    private var matcherUtilityClass: Class<*>? = null
    private val appComponent: ComponentName? by lazy {
        runCatching {
            mContext.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)?.component
        }.getOrNull()
    }

    override fun updatePrefs(vararg key: String) {}

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
        matcherUtilityClass = findClass(
            "com.android.launcher3.search.StringMatcherUtility",
            suppressError = true
        )

        matcherUtilityClass
            .hookMethod("matches")
            .suppressError()
            .runAfter { param ->
                if (param.result == true || matching.get() == true) return@runAfter

                val query = param.args.getOrNull(0) as? String ?: return@runAfter
                val target = param.args.getOrNull(1) as? String ?: return@runAfter
                if (!target.equals(APP_LABEL, ignoreCase = true)) return@runAfter

                param.result = matchesAlias(query, param.args.getOrNull(2))
            }

        findClass("android.app.search.SearchSession", suppressError = true)
            .hookMethod("query")
            .suppressError()
            .runBefore { param ->
                val query = param.args.getOrNull(0)?.let {
                    runCatching { Reflection.callMethod(it, "getInput") as? String }.getOrNull()
                } ?: return@runBefore
                @Suppress("UNCHECKED_CAST")
                val callback = param.args.getOrNull(2) as? Consumer<List<Any>> ?: return@runBefore
                if (!matchesAlias(query, null)) return@runBefore

                param.args[2] = Consumer<List<Any>> { results ->
                    callback.accept(runCatching { withAppResult(results) }.getOrDefault(results))
                }
            }
    }

    private fun withAppResult(results: List<Any>): List<Any> {
        if (results.isEmpty()) return results
        val hasApp = results.any {
            runCatching { Reflection.callMethod(it, "getPackageName") }.getOrNull() == BuildConfig.APPLICATION_ID
        }
        if (hasApp) return results
        val target = appTarget() ?: return results
        return ArrayList<Any>(results.size + 1).apply {
            add(target)
            addAll(results)
        }
    }

    private fun appTarget(): Any? {
        val component = appComponent ?: return null
        val user = Process.myUserHandle()
        return runCatching {
            val builder = Reflection.newInstance(
                Reflection.findClass($$"android.app.search.SearchTarget$Builder", null),
                RESULT_TYPE_APPLICATION,
                LAYOUT_ICON,
                "APPLICATION:${user.hashCode()}:${component.packageName}/${component.className}"
            )
            Reflection.callMethod(builder, "setPackageName", component.packageName)
            Reflection.callMethod(builder, "setUserHandle", user)
            Reflection.callMethod(
                builder,
                "setExtras",
                Bundle().apply { putString(EXTRA_CLASS, component.className) }
            )
            Reflection.callMethod(builder, "build")
        }.getOrNull()
    }

    private fun matchesAlias(query: String, matcher: Any?): Boolean {
        if (query.isBlank()) return false
        val utility = matcherUtilityClass
        matching.set(true)
        try {
            return ALIASES.any { alias ->
                if (utility == null) {
                    alias.contains(query.trim(), ignoreCase = true)
                } else {
                    runCatching {
                        val stringMatcher = matcher ?: Reflection.newInstance(
                            Reflection.findClass(
                                $$"com.android.launcher3.search.StringMatcherUtility$StringMatcher",
                                utility.classLoader
                            )
                        )
                        Reflection.callStaticMethod(
                            utility,
                            "matches",
                            query.lowercase(),
                            alias,
                            stringMatcher
                        ) == true
                    }.getOrDefault(false)
                }
            }
        } finally {
            matching.remove()
        }
    }

    companion object {
        private const val APP_LABEL = "PLEnhanced"
        private const val RESULT_TYPE_APPLICATION = 1
        private const val LAYOUT_ICON = "icon"
        private const val EXTRA_CLASS = "class"
        private val ALIASES = listOf("Pixel Launcher Enhanced", "PixelLauncher Enhanced")
    }
}
