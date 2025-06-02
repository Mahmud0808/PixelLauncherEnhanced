package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEVELOPER_OPTIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ENTRY_IN_LAUNCHER_SETTINGS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Proxy

class LauncherSettings(context: Context) : ModPack(context) {

    private var devOptionsEnabled = false
    private var entryInLauncher = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            devOptionsEnabled = getBoolean(DEVELOPER_OPTIONS, false)
            entryInLauncher = getBoolean(ENTRY_IN_LAUNCHER_SETTINGS, true)
        }
    }

    @Suppress("deprecation")
    @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherSettingsFragmentClass = findClass(
            "com.android.launcher3.SettingsActivity\$LauncherSettingsFragment",
            "com.android.launcher3.settings.SettingsActivity\$LauncherSettingsFragment"
        )
        val featureFlagsClass = findClass("com.android.launcher3.config.FeatureFlags")

        if (mContext.packageName == PIXEL_LAUNCHER_PACKAGE) {
            launcherSettingsFragmentClass
                .hookMethod("initPreference")
                .runBefore { param ->
                    val preference = param.args[0]
                    val key = preference.callMethodSilently("getKey")
                        ?: preference.getField("mKey") as String

                    if (key == "pref_developer_options") {
                        param.result = devOptionsEnabled
                    }
                }

            featureFlagsClass
                .hookMethod("showFlagTogglerUi")
                .suppressError()
                .runBefore { param ->
                    param.result = devOptionsEnabled
                }
        }

        val preferenceClass = findClass("androidx.preference.Preference")!!
        val preferenceClickListenerClass: Class<*>? = preferenceClass.methods
            .find { it.name == "setOnPreferenceClickListener" }
            ?.parameterTypes
            ?.firstOrNull()

        launcherSettingsFragmentClass
            .hookMethod("onCreatePreferences")
            .runAfter { param ->
                if (!entryInLauncher) return@runAfter

                val preferenceScreen = param.thisObject.callMethod("getPreferenceScreen")
                val launchIntent: Intent = mContext.packageManager
                    .getLaunchIntentForPackage(BuildConfig.APPLICATION_ID) ?: return@runAfter
                val activity = param.thisObject.callMethod("getActivity")
                val thisTitle = activity.callMethod("getTitle")
                val expectedTitle = try {
                    mContext.resources.getString(
                        mContext.resources.getIdentifier(
                            "settings_button_text",
                            "string",
                            mContext.packageName
                        )
                    )
                } catch (_: Throwable) {
                    mContext.resources.getString(
                        mContext.resources.getIdentifier(
                            "settings_title",
                            "string",
                            mContext.packageName
                        )
                    )
                }

                if (thisTitle != expectedTitle) return@runAfter

                val myPreference = preferenceClass
                    .getDeclaredConstructor(
                        Context::class.java,
                        AttributeSet::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .newInstance(
                        mContext,
                        null,
                        android.R.attr.preferenceStyle,
                        0
                    )

                myPreference.callMethod("setKey", BuildConfig.APPLICATION_ID)
                myPreference.callMethod("setTitle", modRes.getString(R.string.app_name_shortened))
                myPreference.callMethod("setSummary", modRes.getString(R.string.app_motto))

                if (mContext.packageName == LAUNCHER3_PACKAGE) {
                    myPreference.callMethod(
                        "setIcon",
                        modRes.getDrawable(R.drawable.ic_launcher_foreground)
                    )

                    val layoutResource = mContext.resources.getIdentifier(
                        "settings_layout",
                        "layout",
                        mContext.packageName
                    )
                    if (layoutResource != 0) {
                        myPreference.callMethod("setLayoutResource", layoutResource)
                    }
                }

                val listener = Proxy.newProxyInstance(
                    preferenceClass.classLoader,
                    arrayOf(preferenceClickListenerClass)
                ) { _, _, args ->
                    mContext.startActivity(launchIntent)
                    true
                }

                myPreference.callMethod("setOnPreferenceClickListener", listener)
                preferenceScreen.callMethod("addPreference", myPreference)

                myPreference.javaClass
                    .hookMethod("onBindViewHolder")
                    .runBefore { param ->
                        val mKey = param.thisObject.getFieldSilently("mKey") as? String

                        if (mKey == BuildConfig.APPLICATION_ID) {
                            param.thisObject.setField("mAllowDividerAbove", false)
                            param.thisObject.setField("mAllowDividerBelow", false)
                        }
                    }
                    .runAfter { param ->
                        val holder = param.args[0]
                        val itemView = holder.getField("itemView") as View
                        val mKey = param.thisObject.getFieldSilently("mKey") as? String
                        val selectableBackground = TypedValue().apply {
                            mContext.theme.resolveAttribute(
                                android.R.attr.selectableItemBackground,
                                this,
                                true
                            )
                        }.resourceId

                        if (mKey == BuildConfig.APPLICATION_ID) {
                            itemView.setBackgroundResource(selectableBackground)
                        }
                    }
            }
    }
}