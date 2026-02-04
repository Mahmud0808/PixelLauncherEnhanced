package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import de.robv.android.xposed.XposedHelpers.callStaticMethod
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class IconUpdater(context: Context) : ModPack(context) {

    override fun updatePrefs(vararg key: String) {}

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val launcherModelClass = findClass("com.android.launcher3.LauncherModel")
        val baseActivityClass = findClass("com.android.launcher3.BaseActivity")
        val modelInitializerClass = findClass("com.android.launcher3.model.ModelInitializer")
        val packageUserKeyClass = findClass("com.android.launcher3.util.PackageUserKey")
        val userManager = mContext.getSystemService(UserManager::class.java) as UserManager

        fun hookOnAppIconChanged(paramThisObject: Any?, launcherModel: Any?, type: HookType) {
            baseActivityClass
                .hookMethod("onResume")
                .runAfter {
                    try {
                        val myUserId = callStaticMethod(
                            UserHandle::class.java,
                            "getUserId",
                            Process.myUid()
                        ) as Int

                        if (paramThisObject == null) return@runAfter

                        when (type) {
                            HookType.LauncherModel -> {
                                paramThisObject.callMethod(
                                    "onAppIconChanged",
                                    BuildConfig.APPLICATION_ID,
                                    UserHandle.getUserHandleForUid(myUserId)
                                )

                                userManager.userProfiles.forEach { userHandle ->
                                    paramThisObject.callMethod(
                                        "onAppIconChanged",
                                        BuildConfig.APPLICATION_ID,
                                        userHandle
                                    )
                                }
                            }

                            HookType.ModelInitializer -> {
                                val packageUserKey = packageUserKeyClass!!.getConstructor(
                                    String::class.java,
                                    UserHandle::class.java
                                ).newInstance(
                                    BuildConfig.APPLICATION_ID,
                                    UserHandle.getUserHandleForUid(myUserId)
                                )
                                paramThisObject.callMethod(
                                    "onAppIconChanged",
                                    launcherModel,
                                    packageUserKey
                                )

                                userManager.userProfiles.forEach { userHandle ->
                                    val packageUserKey = packageUserKeyClass.getConstructor(
                                        String::class.java,
                                        UserHandle::class.java
                                    ).newInstance(
                                        BuildConfig.APPLICATION_ID,
                                        userHandle
                                    )
                                    paramThisObject.callMethod(
                                        "onAppIconChanged",
                                        launcherModel,
                                        packageUserKey
                                    )
                                }
                            }
                        }
                    } catch (throwable: Throwable) {
                        log(this@IconUpdater, throwable)
                    }
                }
        }

        launcherModelClass
            .hookConstructor()
            .runAfter { param ->
                val launcherModel = param.thisObject

                if (launcherModelClass.hasMethod(
                        "onAppIconChanged",
                        String::class.java,
                        UserHandle::class.java
                    )
                ) {
                    hookOnAppIconChanged(
                        param.thisObject,
                        launcherModel,
                        HookType.LauncherModel
                    )
                } else {
                    modelInitializerClass
                        .hookConstructor()
                        .runAfter { param ->
                            hookOnAppIconChanged(
                                param.thisObject,
                                launcherModel,
                                HookType.ModelInitializer
                            )
                        }
                }
            }
    }

    private enum class HookType {
        LauncherModel,
        ModelInitializer
    }
}