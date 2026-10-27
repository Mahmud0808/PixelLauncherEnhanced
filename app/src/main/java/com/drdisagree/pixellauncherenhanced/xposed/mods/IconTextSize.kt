package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_ICON_SIZE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER_TEXT_SIZE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class IconTextSize(context: Context) : ModPack(context) {

    private var iconSizeModifier = 1f
    private var textSizeModifier = 1f

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            iconSizeModifier = getSliderInt(LAUNCHER_ICON_SIZE, 100) / 100f
            textSizeModifier = getSliderInt(LAUNCHER_TEXT_SIZE, 100) / 100f
        }

        when (key.firstOrNull()) {
            in setOf(
                LAUNCHER_ICON_SIZE,
                LAUNCHER_TEXT_SIZE
            ) -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")
        val deviceProfileBuilderClass = findClass(
            $$"com.android.launcher3.DeviceProfile$Builder",
            "com.android.launcher3.deviceprofile.DeviceProfileBuilder"
        )

        fun Any.hookDeviceProfile() {
            val workspace = getAnyFieldSilently("mWorkspaceProfile", "workspaceProfile") ?: this
            if (workspace.getExtraFieldSilently(SCALED) != true) IconFit.fitIconsToCells(this, mContext)

            if (getFieldSilently("iconSizePx") is Int) {
                scaleOnce {
                    scaleField("iconSizePx", iconSizeModifier)
                    scaleField("folderIconSizePx", iconSizeModifier)
                    scaleField("folderChildIconSizePx", iconSizeModifier)
                    scaleField("allAppsIconSizePx", iconSizeModifier)
                    scaleField("iconTextSizePx", textSizeModifier)
                    scaleField("folderLabelTextSizePx", textSizeModifier)
                    scaleField("folderChildTextSizePx", textSizeModifier)
                    scaleField("allAppsIconTextSizePx", textSizeModifier)
                    scaleField("folderCellWidthPx", iconSizeModifier)
                    scaleField("folderCellHeightPx", iconSizeModifier)
                }
            } else {
                getAnyField("mWorkspaceProfile", "workspaceProfile")?.scaleOnce {
                    scaleField("iconSizePx", iconSizeModifier)
                    scaleField("iconTextSizePx", textSizeModifier)
                }
                getAnyField("mFolderProfile", "folderProfile")?.scaleOnce {
                    scaleField("folderIconSizePx", iconSizeModifier)
                    scaleField("labelTextSizePx", textSizeModifier)
                    scaleField("childIconSizePx", iconSizeModifier)
                    scaleField("childTextSizePx", textSizeModifier)
                    scaleField("cellWidthPx", iconSizeModifier)
                    scaleField("cellHeightPx", iconSizeModifier)
                }
                getAnyField("mAllAppsProfile", "allAppsProfile")?.scaleOnce {
                    scaleField("iconSizePx", iconSizeModifier)
                    scaleField("iconTextSizePx", textSizeModifier)
                }
                scaleOnce {
                    scaleField("folderIconSizePx", iconSizeModifier)
                    scaleField("folderLabelTextSizePx", textSizeModifier)
                }
            }
        }

        deviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                param.thisObject.hookDeviceProfile()
            }

        deviceProfileBuilderClass
            .hookMethod("build")
            .runAfter { param ->
                try {
                    param.result.hookDeviceProfile()
                } catch (_: Throwable) {
                }
            }
    }

    private fun Any.scaleOnce(block: Any.() -> Unit) {
        if (getExtraFieldSilently(SCALED) == true) return
        setExtraField(SCALED, true)
        block()
    }

    private fun Any.scaleField(field: String, modifier: Float) {
        when (val value = getFieldSilently(field)) {
            is Int -> setFieldSilently(field, (value * modifier).toInt())
            is Float -> setFieldSilently(field, value * modifier)
        }
    }

    companion object {
        private const val SCALED = "pleIconTextScaled"
    }
}
