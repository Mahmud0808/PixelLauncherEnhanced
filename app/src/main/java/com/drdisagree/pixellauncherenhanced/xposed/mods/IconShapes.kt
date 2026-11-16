package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.content.res.Resources
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_SHAPE_PATH
import com.drdisagree.pixellauncherenhanced.utils.IconShapePaths
import com.drdisagree.pixellauncherenhanced.xposed.core.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.hook.MethodHookHelper
import com.drdisagree.pixellauncherenhanced.xposed.hook.findClass
import com.drdisagree.pixellauncherenhanced.xposed.reflection.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.reflection.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.hook.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.reflection.setField
import com.drdisagree.pixellauncherenhanced.xposed.reflection.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.prefs.XPrefs.Xprefs
import com.drdisagree.pixellauncherenhanced.xposed.reflection.Reflection
import com.drdisagree.pixellauncherenhanced.xposed.core.LoadedPackage

class IconShapes(context: Context) : ModPack(context) {

    private var shapePath = ""
    private var loadedPath: String? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            shapePath = getString(ICON_SHAPE_PATH, "").orEmpty()
        }

        when (key.firstOrNull()) {
            ICON_SHAPE_PATH -> if (shapePath != loadedPath) restartLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadedPackage: LoadedPackage) {
        loadedPath = shapePath
        if (shapePath.isEmpty()) return

        val path = shapePath
        val stamp = "shape2:${path.hashCode()}"

        val maskResId = Resources.getSystem().getIdentifier("config_icon_mask", "string", "android")
        if (maskResId != 0) {
            Reflection.findMethodExactIfExists(
                Resources::class.java,
                "getString",
                Int::class.javaPrimitiveType
            )?.let { method ->
                MethodHookHelper(method).runBefore { param ->
                    if (param.args[0] == maskResId) param.result = path
                }
            }
        }

        findClass("com.android.launcher3.shapes.ShapesProvider", suppressError = true)
            ?.let { runCatching { Reflection.getStaticObjectField(it, "iconShapes") }.getOrNull() as? Array<*> }
            ?.filterNotNull()
            ?.forEach { model -> runCatching { model.setField("pathString", path) } }

        IconShapePaths.toPath(path)?.let { fullPath ->
            val genericShapeClass = findClass(
                $$"com.android.launcher3.graphics.ShapeDelegate$GenericPathShape",
                suppressError = true
            )
            genericShapeClass?.declaredMethods
                ?.filter { it.name == "addToPath" }
                ?.forEach { method ->
                    MethodHookHelper(method).runBefore { param ->
                        val target = param.args.firstNotNullOfOrNull { arg ->
                            arg as? Path ?: arg?.getFieldSilently("path") as? Path
                        } ?: return@runBefore
                        val floats = param.args.filterIsInstance<Float>()
                        if (floats.size < 3) return@runBefore

                        val scale = floats[2] / (IconShapePaths.SIZE / 2)
                        val matrix = Matrix().apply {
                            setScale(scale, scale)
                            postTranslate(floats[0], floats[1])
                        }
                        fullPath.transform(matrix, target)
                        param.args.firstOrNull { it?.getFieldSilently("bounds") is RectF }
                            ?.let { wrapper -> target.computeBounds(wrapper.getFieldSilently("bounds") as RectF, true) }
                        param.result = null
                    }
                }
        }

        findClass("com.android.launcher3.graphics.ThemeManager", suppressError = true)
            .hookMethod("parseIconState")
            .suppressError()
            .runAfter { param ->
                val state = param.result ?: return@runAfter
                state.getFieldSilently("iconShape")?.let { state.setFieldSilently("folderShape", it) }
            }

        val iconProviderClass = findClass("com.android.launcher3.icons.IconProvider", suppressError = true)

        iconProviderClass
            .hookMethod("updateSystemState")
            .suppressError()
            .runAfter { param ->
                when (val state = param.thisObject.getFieldSilently("mSystemState")) {
                    is String -> if (!state.contains(stamp)) param.thisObject.setField("mSystemState", "$state $stamp")
                    null -> Unit
                    else -> state.callMethodSilently("withAdditionalValues", arrayOf(stamp))
                        ?.let { param.thisObject.setField("mSystemState", it) }
                }
            }

        iconProviderClass
            .hookMethod("getSystemIconState")
            .suppressError()
            .runAfter { param ->
                (param.result as? String)?.takeIf { !it.contains(stamp) }?.let { param.result = "$it $stamp" }
            }
    }
}
