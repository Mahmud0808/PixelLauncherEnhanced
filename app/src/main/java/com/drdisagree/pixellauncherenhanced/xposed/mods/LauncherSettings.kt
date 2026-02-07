package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEVELOPER_OPTIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ENTRY_IN_LAUNCHER_SETTINGS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ENTRY_IN_OPTIONS_POPUP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.TOGGLE_HIDE_APPS_IN_OPTIONS_POPUP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UNHIDE_ALL_APPS
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.Arrays

class LauncherSettings(context: Context) : ModPack(context) {

    private var devOptionsEnabled = false
    private var entryInLauncher = true
    private var entryInPopup = false
    private var toggleHideAppsInPopup = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            devOptionsEnabled = getBoolean(DEVELOPER_OPTIONS, false)
            entryInLauncher = getBoolean(ENTRY_IN_LAUNCHER_SETTINGS, true)
            entryInPopup = getBoolean(ENTRY_IN_OPTIONS_POPUP, false)
            toggleHideAppsInPopup = getBoolean(TOGGLE_HIDE_APPS_IN_OPTIONS_POPUP, false)
            HideApps.SHOULD_UNHIDE_ALL_APPS = getBoolean(UNHIDE_ALL_APPS, false)
        }

        when (key.firstOrNull()) {
            TOGGLE_HIDE_APPS_IN_OPTIONS_POPUP -> {
                if (!toggleHideAppsInPopup) {
                    setUnhideAllApps(false)
                }
            }
        }
    }

    @Suppress("deprecation")
    @SuppressLint("DiscouragedApi", "UseCompatLoadingForDrawables")
    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherSettingsFragmentClass = findClass(
            $$"com.android.launcher3.SettingsActivity$LauncherSettingsFragment",
            $$"com.android.launcher3.settings.SettingsActivity$LauncherSettingsFragment"
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
        var preferenceClickListenerFieldName: String? = null
        val preferenceClickListenerClass: Class<*>? = preferenceClass.methods
            .firstOrNull { it.name == "setOnPreferenceClickListener" }
            ?.parameterTypes
            ?.firstOrNull()
            ?: preferenceClass.declaredFields
                .firstOrNull { field ->
                    field.name.endsWith("OnClickListener", ignoreCase = true) ||
                            field.name.endsWith("OnPreferenceClickListener", ignoreCase = true)
                }
                ?.also { field ->
                    preferenceClickListenerFieldName = field.name
                }
                ?.type

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

                if (myPreference.hasMethod("setKey", String::class.java)) {
                    myPreference.callMethod("setKey", BuildConfig.APPLICATION_ID)
                } else {
                    myPreference.setField("mKey", BuildConfig.APPLICATION_ID)
                }
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
                ) { _, _, _ ->
                    mContext.startActivity(launchIntent)
                    true
                }

                if (myPreference.hasMethod("setOnPreferenceClickListener")) {
                    myPreference.callMethod("setOnPreferenceClickListener", listener)
                } else if (preferenceClickListenerFieldName != null) {
                    myPreference.setField(preferenceClickListenerFieldName, listener)
                } else {
                    log(
                        this@LauncherSettings,
                        "No supported method found for preferenceClickListener."
                    )
                }

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

        val optionsPopupViewClass = findClass("com.android.launcher3.views.OptionsPopupView")
        val optionItemClass =
            findClass($$"com.android.launcher3.views.OptionsPopupView$OptionItem")!!
        val launcherEventEnum =
            findClass($$"com.android.launcher3.logging.StatsLogManager$LauncherEvent")!!
        val eventEnum = findClass($$"com.android.launcher3.logging.StatsLogManager$EventEnum")!!
        val optionItemConstructors = optionItemClass.declaredConstructors

        optionItemClass
            .hookConstructor()
            .runBefore { param ->
                if (!entryInPopup && !toggleHideAppsInPopup) return@runBefore

                if (param.args[0] is Context) {
                    val context = param.args[0] as Context
                    val labelRes = param.args[1] as Int
                    val iconRes = param.args[2] as Int
                    val eventId = param.args[3]
                    val clickListener = param.args[4]

                    when (labelRes) {
                        -1 if iconRes == -1 -> {
                            param.thisObject.apply {
                                setField("labelRes", labelRes)
                                setField("label", modRes.getString(R.string.app_name_shortened))
                                setField(
                                    "icon",
                                    modRes.getDrawable(R.drawable.ic_launcher_foreground)
                                )
                                setField("eventId", eventId)
                                setField("clickListener", clickListener)
                            }
                        }

                        -2 if iconRes == -2 -> {
                            param.thisObject.apply {
                                setField("labelRes", labelRes)
                                setField(
                                    "label",
                                    if (HideApps.SHOULD_UNHIDE_ALL_APPS) modRes.getString(R.string.hide_apps)
                                    else modRes.getString(R.string.unhide_apps)
                                )
                                setField(
                                    "icon",
                                    if (HideApps.SHOULD_UNHIDE_ALL_APPS) modRes.getDrawable(R.drawable.ic_visibility_lock)
                                    else modRes.getDrawable(R.drawable.ic_visibility)
                                )
                                setField("eventId", eventId)
                                setField("clickListener", clickListener)
                            }
                        }

                        else -> {
                            param.thisObject.apply {
                                setField("labelRes", labelRes)
                                setField("label", context.getString(labelRes))
                                setField("icon", context.getDrawable(iconRes))
                                setField("eventId", eventId)
                                setField("clickListener", clickListener)
                            }
                        }
                    }
                } else {
                    val label = param.args[0] as CharSequence
                    val icon = param.args[1] as Drawable
                    val eventId = param.args[2]
                    val clickListener = param.args[3]

                    param.thisObject.apply {
                        setField("labelRes", 0)
                        setField("label", label)
                        setField("icon", icon)
                        setField("eventId", eventId)
                        setField("clickListener", clickListener)
                    }
                }

                param.result = null
            }

        @Suppress("UNCHECKED_CAST")
        optionsPopupViewClass
            .hookMethod("getOptions")
            .runAfter { param ->
                if (!entryInPopup && !toggleHideAppsInPopup) return@runAfter

                val launcher = param.args[0]
                val options = param.result as ArrayList<Any>

                val eventId = launcherEventEnum.enumConstants?.let {
                    Arrays.stream(it)
                        .filter { c: Any -> c.toString() == "LAUNCHER_SETTINGS_BUTTON_TAP_OR_LONGPRESS" }
                        .findFirst().get()
                }!!

                if (toggleHideAppsInPopup) {
                    val clickListener = View.OnLongClickListener {
                        setUnhideAllApps(!HideApps.SHOULD_UNHIDE_ALL_APPS)
                        true
                    }

                    val optionItem = when {
                        optionItemConstructors.any {
                            it.parameterTypes.contentEquals(
                                arrayOf(
                                    CharSequence::class.java,
                                    Drawable::class.java,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                            )
                        } -> {
                            optionItemClass
                                .getDeclaredConstructor(
                                    CharSequence::class.java,
                                    Drawable::class.java,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                                .newInstance(
                                    if (HideApps.SHOULD_UNHIDE_ALL_APPS) modRes.getString(R.string.hide_apps)
                                    else modRes.getString(R.string.unhide_apps),
                                    if (HideApps.SHOULD_UNHIDE_ALL_APPS) modRes.getDrawable(R.drawable.ic_visibility_lock)
                                    else modRes.getDrawable(R.drawable.ic_visibility),
                                    eventId,
                                    clickListener
                                )
                        }

                        optionItemConstructors.any {
                            it.parameterTypes.contentEquals(
                                arrayOf(
                                    Context::class.java,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                            )
                        } -> {
                            optionItemClass
                                .getDeclaredConstructor(
                                    Context::class.java,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                                .newInstance(
                                    launcher,
                                    -2,
                                    -2,
                                    eventId,
                                    clickListener
                                )
                        }

                        else -> {
                            log("No supported constructor found for optionItemClass.")
                            null
                        }
                    }
                    if (optionItem != null) {
                        options.add(optionItem)
                    }
                }

                if (entryInPopup) {
                    val clickListener = object : View.OnLongClickListener {
                        override fun onLongClick(p0: View?): Boolean {
                            val launchIntent: Intent = mContext.packageManager
                                .getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
                                ?: return false
                            mContext.startActivity(launchIntent)
                            return true
                        }
                    }

                    val optionItem = when {
                        optionItemConstructors.any {
                            it.parameterTypes.contentEquals(
                                arrayOf(
                                    CharSequence::class.java,
                                    Drawable::class.java,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                            )
                        } -> {
                            optionItemClass
                                .getDeclaredConstructor(
                                    CharSequence::class.java,
                                    Drawable::class.java,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                                .newInstance(
                                    modRes.getString(R.string.app_name_shortened),
                                    modRes.getDrawable(R.drawable.ic_launcher_foreground),
                                    eventId,
                                    clickListener
                                )
                        }

                        optionItemConstructors.any {
                            it.parameterTypes.contentEquals(
                                arrayOf(
                                    Context::class.java,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                            )
                        } -> {
                            optionItemClass
                                .getDeclaredConstructor(
                                    Context::class.java,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    eventEnum,
                                    View.OnLongClickListener::class.java
                                )
                                .newInstance(
                                    launcher,
                                    -1,
                                    -1,
                                    eventId,
                                    clickListener
                                )
                        }

                        else -> {
                            log("No supported constructor found for optionItemClass.")
                            null
                        }
                    }
                    if (optionItem != null) {
                        options.add(optionItem)
                    }
                }

                param.result = options
            }
    }

    fun setUnhideAllApps(value: Boolean) {
        HideApps.SHOULD_UNHIDE_ALL_APPS = value
        @SuppressLint("ApplySharedPref")
        Xprefs.edit()
            .putBoolean(UNHIDE_ALL_APPS, value)
            .commit()
        CoroutineScope(Dispatchers.Main).launch {
            delay(300)
            HideApps.updateLauncherIcons(mContext)
        }
    }
}