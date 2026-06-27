package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FORCE_STOP_APP_IN_POPUP
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.dumpClass
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.stream.Stream

class ForceStopShortcut(context: Context) : ModPack(context) {

    private var forceStopInPopup = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            forceStopInPopup = getBoolean(FORCE_STOP_APP_IN_POPUP, false)
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        log(TAG, "=== ForceStopShortcut initialization starting ===")

        // ========== PHASE 1: Discover classes ==========
        val systemShortcutClass = findClass(
            "com.android.launcher3.popup.SystemShortcut",
            suppressError = true
        )
        if (systemShortcutClass == null) {
            log(TAG, "FATAL: SystemShortcut class not found!")
            return
        }
        log(TAG, "SystemShortcut class found: ${systemShortcutClass.name}")

        // Dump SystemShortcut to understand its structure
        log(TAG, "Dumping SystemShortcut class structure...")
        systemShortcutClass.dumpClass()

        // ========== PHASE 2: Discover Factory interface ==========
        // The Factory interface is nested inside SystemShortcut
        var factoryClass: Class<*>? = null
        for (innerClass in systemShortcutClass.declaredClasses) {
            log(TAG, "  Inner class: ${innerClass.name}, isInterface=${innerClass.isInterface}, methods=${innerClass.methods.size}")
            if (innerClass.isInterface) {
                for (m in innerClass.methods) {
                    log(TAG, "    Interface method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
                }
                // The Factory interface has a single abstract method: getShortcut
                if (innerClass.methods.any { it.name == "getShortcut" }) {
                    factoryClass = innerClass
                    log(TAG, "  -> Found Factory interface by getShortcut method: ${innerClass.name}")
                } else if (factoryClass == null && innerClass.isInterface) {
                    // Fallback: any interface with exactly one abstract method
                    val abstractMethods = innerClass.methods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
                    if (abstractMethods.size == 1) {
                        factoryClass = innerClass
                        log(TAG, "  -> Found Factory interface by SAM detection: ${innerClass.name}")
                    }
                }
            }
        }

        if (factoryClass == null) {
            log(TAG, "WARNING: Factory interface not found in declaredClasses. Trying classes...")
            for (innerClass in systemShortcutClass.classes) {
                log(TAG, "  Public inner class: ${innerClass.name}, isInterface=${innerClass.isInterface}")
                if (innerClass.isInterface) {
                    factoryClass = innerClass
                    log(TAG, "  -> Using first public interface: ${innerClass.name}")
                    break
                }
            }
        }

        if (factoryClass == null) {
            log(TAG, "FATAL: Factory interface not found at all! Cannot proceed with stream injection.")
            // Fall through - we'll try the direct hook approach below
        } else {
            log(TAG, "Factory interface resolved: ${factoryClass.name}")
            setupStreamInjection(systemShortcutClass, factoryClass, loadPackageParam)
        }

        // ========== PHASE 3: Direct populateAndShowRows hook (fallback) ==========
        setupPopulateHook(systemShortcutClass, loadPackageParam)

        log(TAG, "=== ForceStopShortcut initialization complete ===")
    }

    /**
     * Strategy 1: Hook getSupportedShortcuts to inject our Factory into the stream.
     * This is the "clean" approach matching crDroid's native integration.
     */
    private fun setupStreamInjection(
        systemShortcutClass: Class<*>,
        factoryClass: Class<*>,
        loadPackageParam: LoadPackageParam
    ) {
        log(TAG, "Setting up Stream injection strategy...")

        // Find the factory method name
        val factoryMethodName = factoryClass.methods
            .firstOrNull { java.lang.reflect.Modifier.isAbstract(it.modifiers) }?.name
            ?: factoryClass.methods.firstOrNull()?.name
            ?: "getShortcut"
        log(TAG, "Factory method name: $factoryMethodName")

        // Create our Factory proxy
        val forceStopFactory = try {
            java.lang.reflect.Proxy.newProxyInstance(
                factoryClass.classLoader,
                arrayOf(factoryClass)
            ) { _, method, args ->
                if (method.name == factoryMethodName && args != null && args.size >= 3) {
                    if (!forceStopInPopup) return@newProxyInstance null
                    try {
                        return@newProxyInstance createForceStopShortcutInstance(
                            systemShortcutClass, args[0], args[1], args[2]
                        )
                    } catch (e: Throwable) {
                        log(TAG, "Factory proxy error: ${e.message}")
                        e.printStackTrace()
                    }
                }
                null
            }
        } catch (e: Throwable) {
            log(TAG, "FATAL: Failed to create Factory proxy: ${e.message}")
            return
        }
        log(TAG, "Factory proxy created successfully")

        // Hook getSupportedShortcuts on all known launcher classes
        val launcherClassNames = listOf(
            "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
            "com.android.launcher3.uioverrides.QuickstepLauncher",
            "com.android.launcher3.Launcher"
        )

        var hookedAny = false
        for (className in launcherClassNames) {
            val clazz = findClass(className, suppressError = true) ?: continue
            log(TAG, "Found launcher class: $className")

            // Find methods that return Stream and have 0 or 1 parameters
            val streamMethods = clazz.declaredMethods.filter {
                it.returnType == Stream::class.java
            }
            log(TAG, "  Stream-returning methods in $className: ${streamMethods.size}")
            for (m in streamMethods) {
                log(TAG, "    ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
            }

            // Hook getSupportedShortcuts specifically by name first
            val targetMethods = streamMethods.filter {
                it.name == "getSupportedShortcuts" ||
                (it.parameterTypes.size == 1 && it.returnType == Stream::class.java)
            }

            for (method in targetMethods) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!forceStopInPopup) return
                            val originalStream = param.result as? Stream<*> ?: return

                            log(TAG, "getSupportedShortcuts hook fired in ${param.thisObject.javaClass.name}")

                            // Collect original stream into a list, append our factory, re-stream
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val originalList = (originalStream as Stream<Any>).collect(
                                    java.util.stream.Collectors.toList()
                                )
                                val newList = ArrayList(originalList)
                                newList.add(forceStopFactory)
                                param.result = newList.stream()
                                log(TAG, "Successfully appended ForceStop factory. Total shortcuts: ${newList.size}")
                            } catch (e: Throwable) {
                                log(TAG, "Stream injection error: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    })
                    hookedAny = true
                    log(TAG, "  Hooked method: ${method.name} in $className")
                } catch (e: Throwable) {
                    log(TAG, "  Failed to hook ${method.name}: ${e.message}")
                }
            }
        }

        if (!hookedAny) {
            log(TAG, "WARNING: No Stream-returning methods were hooked!")
        }

        // Hook onClick on SystemShortcut to intercept our tagged instances
        setupOnClickHook(systemShortcutClass)

        // Hook setIconAndLabel methods to customize our shortcut's appearance
        setupIconLabelHook(systemShortcutClass)
    }

    /**
     * Create an instance of a concrete SystemShortcut subclass and tag it as ForceStop.
     */
    private fun createForceStopShortcutInstance(
        systemShortcutClass: Class<*>,
        context: Any,
        itemInfo: Any,
        originalView: Any
    ): Any? {
        // Try to find AppInfo inner class (most common, always available)
        val appInfoClass = systemShortcutClass.declaredClasses.firstOrNull {
            it.simpleName == "AppInfo" && systemShortcutClass.isAssignableFrom(it)
        }

        val concreteClass = appInfoClass ?: systemShortcutClass.declaredClasses.firstOrNull {
            !java.lang.reflect.Modifier.isAbstract(it.modifiers) &&
            systemShortcutClass.isAssignableFrom(it) &&
            it.constructors.isNotEmpty()
        }

        if (concreteClass == null) {
            log(TAG, "No concrete SystemShortcut subclass found")
            return null
        }

        log(TAG, "Using concrete class: ${concreteClass.name}")

        // Try constructors in order of specificity
        for (constructor in concreteClass.constructors.sortedByDescending { it.parameterTypes.size }) {
            try {
                val args = Array(constructor.parameterTypes.size) { i ->
                    val paramType = constructor.parameterTypes[i]
                    when {
                        paramType.isAssignableFrom(context.javaClass) -> context
                        paramType.isAssignableFrom(itemInfo.javaClass) -> itemInfo
                        paramType.isAssignableFrom(originalView.javaClass) -> originalView
                        paramType == Int::class.javaPrimitiveType || paramType == Int::class.java -> 0
                        paramType == Boolean::class.javaPrimitiveType || paramType == Boolean::class.java -> true
                        else -> {
                            log(TAG, "  Constructor param[$i] type ${paramType.name} - cannot match, trying null")
                            null
                        }
                    }
                }

                val instance = constructor.newInstance(*args)
                XposedHelpers.setAdditionalInstanceField(instance, "isForceStop", true)
                log(TAG, "Created ForceStop shortcut instance via ${concreteClass.simpleName}")
                return instance
            } catch (e: Throwable) {
                log(TAG, "Constructor with ${constructor.parameterTypes.size} params failed: ${e.message}")
            }
        }

        log(TAG, "All constructors failed for ${concreteClass.name}")
        return null
    }

    /**
     * Hook onClick on SystemShortcut (and subclasses) to handle our ForceStop shortcut.
     */
    private fun setupOnClickHook(systemShortcutClass: Class<*>) {
        // onClick is defined in SystemShortcut which implements View.OnClickListener
        // We need to hook it on the base class
        try {
            val onClickMethod = systemShortcutClass.getMethod("onClick", View::class.java)
            XposedBridge.hookMethod(onClickMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val isForceStop = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject, "isForceStop"
                    )
                    if (isForceStop != true) return

                    param.result = null // Prevent default onClick

                    try {
                        val view = param.args[0] as View
                        val ctx = view.context

                        // Get package name from the shortcut's mItemInfo field
                        val packageName = extractPackageName(systemShortcutClass, param.thisObject)
                        if (packageName != null) {
                            forceStopApp(ctx, packageName)

                            // Close popup
                            val mTarget = getFieldFromHierarchy(param.thisObject, "mTarget")
                            if (mTarget != null) {
                                findClass("com.android.launcher3.AbstractFloatingView", suppressError = true)
                                    ?.callStaticMethodSilently("closeAllOpenViews", mTarget)
                            }
                        } else {
                            Toast.makeText(ctx, "Could not determine package name", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Throwable) {
                        log(TAG, "ForceStop onClick error: ${e.message}")
                    }
                }
            })
            log(TAG, "onClick hook set on SystemShortcut")
        } catch (e: Throwable) {
            log(TAG, "Failed to hook onClick: ${e.message}")
        }
    }

    /**
     * Hook the icon/label setter methods to customize our ForceStop shortcut appearance.
     */
    private fun setupIconLabelHook(systemShortcutClass: Class<*>) {
        // setIconAndLabelFor(View iconView, TextView labelView)
        try {
            val setIconLabelMethod = systemShortcutClass.declaredMethods.firstOrNull {
                it.name == "setIconAndLabelFor" && it.parameterTypes.size == 2
            }
            if (setIconLabelMethod != null) {
                XposedBridge.hookMethod(setIconLabelMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (XposedHelpers.getAdditionalInstanceField(param.thisObject, "isForceStop") != true) return
                        param.result = null // Prevent original

                        try {
                            val iconView = param.args[0] as View
                            val labelView = param.args[1] as android.widget.TextView
                            val ctx = iconView.context

                            val drawable = getForceStopIcon(ctx)
                            iconView.setBackgroundDrawable(drawable)
                            labelView.text = getForceStopLabel(ctx)
                        } catch (e: Throwable) {
                            log(TAG, "setIconAndLabelFor hook error: ${e.message}")
                        }
                    }
                })
                log(TAG, "Hooked setIconAndLabelFor")
            }
        } catch (e: Throwable) {
            log(TAG, "Failed to hook setIconAndLabelFor: ${e.message}")
        }

        // setIconAndContentDescriptionFor(ImageView view)
        try {
            val setIconMethod = systemShortcutClass.declaredMethods.firstOrNull {
                it.name == "setIconAndContentDescriptionFor" && it.parameterTypes.size == 1
            }
            if (setIconMethod != null) {
                XposedBridge.hookMethod(setIconMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (XposedHelpers.getAdditionalInstanceField(param.thisObject, "isForceStop") != true) return
                        param.result = null // Prevent original

                        try {
                            val imageView = param.args[0] as ImageView
                            val ctx = imageView.context

                            imageView.setImageDrawable(getForceStopIcon(ctx))
                            imageView.contentDescription = getForceStopLabel(ctx)
                        } catch (e: Throwable) {
                            log(TAG, "setIconAndContentDescriptionFor hook error: ${e.message}")
                        }
                    }
                })
                log(TAG, "Hooked setIconAndContentDescriptionFor")
            }
        } catch (e: Throwable) {
            log(TAG, "Failed to hook setIconAndContentDescriptionFor: ${e.message}")
        }
    }

    /**
     * Strategy 2: Hook populateAndShowRows on PopupContainerWithArrow to directly inject
     * into the systemShortcuts list before it's rendered.
     * This is the fallback if stream injection doesn't work.
     */
    private fun setupPopulateHook(
        systemShortcutClass: Class<*>,
        loadPackageParam: LoadPackageParam
    ) {
        val popupClass = findClass(
            "com.android.launcher3.popup.PopupContainerWithArrow",
            suppressError = true
        )
        if (popupClass == null) {
            log(TAG, "PopupContainerWithArrow class not found")
            return
        }
        log(TAG, "PopupContainerWithArrow found: ${popupClass.name}")

        // Find populateAndShowRows method
        val populateMethods = popupClass.declaredMethods.filter {
            it.name.contains("populateAndShow", ignoreCase = true)
        }
        log(TAG, "populateAndShow* methods found: ${populateMethods.size}")
        for (m in populateMethods) {
            log(TAG, "  ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }

        // Also look at ALL methods to find the one that takes a List<SystemShortcut>
        val allMethods = popupClass.declaredMethods
        for (m in allMethods) {
            if (m.parameterTypes.any { it == java.util.List::class.java }) {
                log(TAG, "  Method with List param: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        }

        // Hook all methods that have a List parameter (this catches populateAndShowRows)
        for (method in allMethods) {
            val listParamIndex = method.parameterTypes.indexOfFirst { it == java.util.List::class.java }
            if (listParamIndex < 0) continue

            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!forceStopInPopup) return

                        log(TAG, "populateAndShowRows hook fired: ${method.name}")

                        try {
                            @Suppress("UNCHECKED_CAST")
                            val shortcuts = param.args[listParamIndex] as? MutableList<Any>
                            if (shortcuts == null) {
                                log(TAG, "  List param is null, trying to wrap...")
                                @Suppress("UNCHECKED_CAST")
                                val immutableList = param.args[listParamIndex] as? List<Any> ?: return
                                val mutableList = ArrayList(immutableList)

                                // Find itemInfo from the popup
                                val popup = param.thisObject
                                val itemInfo = getFieldFromHierarchy(popup, "itemInfo")
                                    ?: getFieldFromHierarchy(popup, "mItemInfo")

                                if (itemInfo != null) {
                                    val forceStopShortcut = createForceStopFromExistingShortcut(
                                        mutableList, systemShortcutClass, itemInfo, popup
                                    )
                                    if (forceStopShortcut != null) {
                                        mutableList.add(forceStopShortcut)
                                        param.args[listParamIndex] = mutableList
                                        log(TAG, "  Injected ForceStop into immutable list copy")
                                    }
                                } else {
                                    log(TAG, "  Could not find itemInfo in popup")
                                }
                                return
                            }

                            // Mutable list path
                            val popup = param.thisObject
                            val itemInfo = getFieldFromHierarchy(popup, "itemInfo")
                                ?: getFieldFromHierarchy(popup, "mItemInfo")

                            if (itemInfo != null) {
                                val forceStopShortcut = createForceStopFromExistingShortcut(
                                    shortcuts, systemShortcutClass, itemInfo, popup
                                )
                                if (forceStopShortcut != null) {
                                    shortcuts.add(forceStopShortcut)
                                    log(TAG, "  Injected ForceStop into mutable list")
                                }
                            } else {
                                log(TAG, "  Could not find itemInfo in popup")
                            }
                        } catch (e: Throwable) {
                            log(TAG, "  populateAndShowRows hook error: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                })
                log(TAG, "Hooked populate method: ${method.name}")
            } catch (e: Throwable) {
                log(TAG, "Failed to hook ${method.name}: ${e.message}")
            }
        }

        // Also hook the PopupController show() methods
        setupPopupControllerHook(systemShortcutClass, loadPackageParam)
    }

    /**
     * Strategy 3: Hook PopupControllerForAppIcon.show() which creates the popup on Android 17+
     */
    private fun setupPopupControllerHook(
        systemShortcutClass: Class<*>,
        loadPackageParam: LoadPackageParam
    ) {
        val controllerClassNames = listOf(
            "com.android.launcher3.popup.PopupControllerForAppIcon",
            "com.android.launcher3.popup.PopupController"
        )

        for (className in controllerClassNames) {
            val clazz = findClass(className, suppressError = true) ?: continue
            log(TAG, "Found popup controller: $className")

            val showMethods = clazz.declaredMethods.filter { it.name == "show" }
            log(TAG, "  show() methods: ${showMethods.size}")
            for (m in showMethods) {
                log(TAG, "    ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
            }
        }
    }

    /**
     * Create a ForceStop shortcut by cloning an existing shortcut from the list.
     */
    @SuppressLint("DiscouragedApi")
    private fun createForceStopFromExistingShortcut(
        existingShortcuts: List<Any>,
        systemShortcutClass: Class<*>,
        itemInfo: Any,
        popup: Any
    ): Any? {
        if (existingShortcuts.isEmpty()) {
            log(TAG, "No existing shortcuts to clone from")
            return null
        }

        val template = existingShortcuts[0]
        val templateClass = template.javaClass
        val isPopupItem = templateClass.name == "com.android.launcher3.popup.ui.PopupItem"

        // ===== Get originalView =====
        val originalView = getFieldFromHierarchy(template, "mOriginalView")
            ?: getFieldFromHierarchy(popup, "originalView")
            ?: getFieldFromHierarchy(popup, "mOriginalView")
            ?: getFieldFromHierarchy(popup, "originalIcon")

        // Try extracting ItemInfo from View tag
        var resolvedItemInfo: Any? = itemInfo
        if (originalView is View && originalView.tag != null) {
            resolvedItemInfo = originalView.tag
            log(TAG, "  Using itemInfo from originalView.tag: ${resolvedItemInfo?.javaClass?.simpleName}")
        }

        // ===== Get target (ActivityContext / Launcher) =====
        var target: Any? = getFieldFromHierarchy(template, "mTarget")
            ?: getFieldFromHierarchy(popup, "mActivityContext")

        if (target == null && originalView is View) {
            try {
                val activityContextClass = findClass("com.android.launcher3.views.ActivityContext", suppressError = true)
                if (activityContextClass != null) {
                    val lookupMethod = activityContextClass.getMethod("lookupContext", Context::class.java)
                    target = lookupMethod.invoke(null, originalView.context)
                }
            } catch (_: Throwable) {}
        }
        if (target == null && originalView is View) {
            try {
                val launcherClass = findClass("com.android.launcher3.Launcher", suppressError = true)
                if (launcherClass != null) {
                    target = launcherClass.callStaticMethodSilently("getLauncher", originalView.context)
                }
            } catch (_: Throwable) {}
        }
        if (target == null && originalView is View) {
            target = originalView.context
        }

        if (originalView !is View || target == null) {
            log(TAG, "FATAL: Cannot create shortcut - missing originalView=$originalView target=$target")
            return null
        }

        val context = originalView.context

        // =========================================================
        // ANDROID 17 QPR1 COMPOSE PATH (PopupItem)
        // =========================================================
        if (isPopupItem) {
            log(TAG, "  Detected Android 17 QPR1 PopupItem architecture!")
            try {
                // Get the category from the template
                val category = getFieldFromHierarchy(template, "category")
                
                // Get the action interface from the template
                val actionField = templateClass.declaredFields.firstOrNull { it.name == "popupAction" }
                    ?: templateClass.declaredFields.firstOrNull { it.type.isInterface }
                
                if (category == null || actionField == null) {
                    log(TAG, "  Failed to extract category ($category) or actionField ($actionField) from PopupItem")
                    return null
                }

                val actionInterface = actionField.type
                log(TAG, "  Action Interface: ${actionInterface.name}")

                // Determine the package name for the Force Stop action
                val packageName = extractPackageName(systemShortcutClass, template) ?: extractPackageNameFromItemInfo(resolvedItemInfo)

                // Create a Proxy for the action interface
                val actionProxy = java.lang.reflect.Proxy.newProxyInstance(
                    templateClass.classLoader,
                    arrayOf(actionInterface)
                ) { _, method, args ->
                    if (method.name != "toString" && method.name != "hashCode" && method.name != "equals") {
                        log(TAG, "  Force Stop action triggered via Proxy!")
                        if (packageName != null) {
                            forceStopApp(context, packageName)
                            
                            // Close popup
                            try {
                                findClass("com.android.launcher3.AbstractFloatingView", suppressError = true)
                                    ?.callStaticMethodSilently("closeAllOpenViews", target)
                            } catch (e: Throwable) {
                                log(TAG, "  Failed to close views: ${e.message}")
                            }
                        } else {
                            Toast.makeText(context, "Cannot find package name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    null
                }

                // MAGIC ID for intercepting the string resource in Compose
                val MAGIC_STRING_ID = Int.MAX_VALUE - 555
                val iconResId = android.R.drawable.ic_dialog_alert // Warning triangle is better for Force Stop
                val labelResId = MAGIC_STRING_ID

                // Hook Resources.getString to return our localized Force Stop string
                try {
                    XposedBridge.hookAllMethods(android.content.res.Resources::class.java, "getString", object : de.robv.android.xposed.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val id = param.args[0] as? Int ?: return
                            if (id == MAGIC_STRING_ID) {
                                // Try to get localized "Force stop" from Settings app
                                try {
                                    val settingsRes = context.packageManager.getResourcesForApplication("com.android.settings")
                                    val forceStopId = settingsRes.getIdentifier("force_stop", "string", "com.android.settings")
                                    if (forceStopId != 0) {
                                        param.result = settingsRes.getString(forceStopId)
                                        return
                                    }
                                } catch (_: Throwable) {}
                                
                                // Fallback
                                param.result = "Force Stop"
                            }
                        }
                    })
                    log(TAG, "  Hooked Resources.getString for Magic ID")
                } catch (e: Throwable) {
                    log(TAG, "  Failed to hook Resources.getString: ${e.message}")
                }

                // Try to instantiate PopupItem(int iconResId, int labelResId, action, category)
                for (constructor in templateClass.constructors) {
                    try {
                        val args = Array(constructor.parameterTypes.size) { i ->
                            val paramType = constructor.parameterTypes[i]
                            when {
                                paramType == Int::class.javaPrimitiveType || paramType == Int::class.java -> {
                                    if (i == 0) iconResId else labelResId
                                }
                                paramType.isInstance(actionProxy) -> actionProxy
                                paramType.isInstance(category) -> category
                                else -> null
                            }
                        }
                        
                        val instance = constructor.newInstance(*args)
                        log(TAG, "  SUCCESS: Created PopupItem via constructor (${constructor.parameterTypes.joinToString { it.simpleName }})")
                        return instance
                    } catch (e: Throwable) {
                        log(TAG, "  PopupItem constructor failed: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                log(TAG, "  Failed to clone PopupItem: ${e.message}")
                e.printStackTrace()
            }
            return null
        }

        // =========================================================
        // LEGACY PATH (SystemShortcut subclasses)
        // =========================================================
        log(TAG, "  Attempting to create legacy ForceStop shortcut with target=${target.javaClass.name}")

        for (constructor in templateClass.constructors.sortedByDescending { it.parameterTypes.size }) {
            try {
                val args = Array(constructor.parameterTypes.size) { i ->
                    val paramType = constructor.parameterTypes[i]
                    when {
                        paramType.isInstance(target) -> target
                        paramType.isInstance(itemInfo) -> itemInfo
                        paramType.isInstance(originalView) -> originalView
                        paramType == Int::class.javaPrimitiveType || paramType == Int::class.java -> 0
                        paramType == Boolean::class.javaPrimitiveType || paramType == Boolean::class.java -> true
                        else -> null
                    }
                }

                val instance = constructor.newInstance(*args)
                XposedHelpers.setAdditionalInstanceField(instance, "isForceStop", true)
                log(TAG, "  SUCCESS: Created ForceStop shortcut clone from ${templateClass.simpleName}")
                return instance
            } catch (e: Throwable) {}
        }

        val appInfoClass = systemShortcutClass.declaredClasses.firstOrNull {
            it.simpleName == "AppInfo" && systemShortcutClass.isAssignableFrom(it)
        }

        if (appInfoClass != null) {
            for (constructor in appInfoClass.constructors.sortedByDescending { it.parameterTypes.size }) {
                try {
                    val args = Array(constructor.parameterTypes.size) { i ->
                        val paramType = constructor.parameterTypes[i]
                        when {
                            paramType.isInstance(target) -> target
                            paramType.isInstance(itemInfo) -> itemInfo
                            paramType.isInstance(originalView) -> originalView
                            paramType == Int::class.javaPrimitiveType || paramType == Int::class.java -> 0
                            paramType == Boolean::class.javaPrimitiveType || paramType == Boolean::class.java -> true
                            else -> null
                        }
                    }

                    val instance = constructor.newInstance(*args)
                    XposedHelpers.setAdditionalInstanceField(instance, "isForceStop", true)
                    log(TAG, "  SUCCESS: Created ForceStop shortcut from AppInfo")
                    return instance
                } catch (e: Throwable) {}
            }
        }

        log(TAG, "  ALL strategies failed to create ForceStop shortcut instance")
        return null
    }

    private fun extractPackageNameFromItemInfo(itemInfo: Any?): String? {
        if (itemInfo == null) return null
        
        val component = itemInfo.callMethodSilently("getTargetComponent") as? android.content.ComponentName
        if (component != null) return component.packageName

        val intent = itemInfo.getFieldSilently("intent") as? android.content.Intent
        if (intent != null) {
            return intent.component?.packageName ?: intent.`package`
        }
        
        val targetPackage = itemInfo.getFieldSilently("targetPackage") as? CharSequence
        if (targetPackage != null) return targetPackage.toString()
        
        return null
    }

    private fun extractPackageName(systemShortcutClass: Class<*>, shortcutInstance: Any): String? {
        // Try mItemInfo.getTargetComponent().getPackageName()
        val itemInfo = getFieldFromHierarchy(shortcutInstance, "mItemInfo")
        if (itemInfo != null) {
            val component = itemInfo.callMethodSilently("getTargetComponent") as? android.content.ComponentName
            if (component != null) return component.packageName

            val intent = itemInfo.getFieldSilently("intent") as? android.content.Intent
            if (intent != null) {
                return intent.component?.packageName ?: intent.`package`
            }
        }

        log(TAG, "Could not extract package name from shortcut")
        return null
    }

    private fun getFieldFromHierarchy(obj: Any, fieldName: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun forceStopApp(context: Context, packageName: String) {
        log(TAG, "Attempting to force stop: $packageName")
        var success = false

        try {
            // Android 8.0+ (Oreo) and newer
            val amClass = de.robv.android.xposed.XposedHelpers.findClass("android.app.ActivityManager", context.classLoader)
            val iam = de.robv.android.xposed.XposedHelpers.callStaticMethod(amClass, "getService")
            if (iam != null) {
                try {
                    de.robv.android.xposed.XposedHelpers.callMethod(iam, "forceStopPackage", packageName, -2) // USER_CURRENT = -2
                    success = true
                } catch (e: Throwable) {
                    try {
                        de.robv.android.xposed.XposedHelpers.callMethod(iam, "forceStopPackage", packageName)
                        success = true
                    } catch (e2: Throwable) {
                        log(TAG, "forceStopApp via IActivityManager inner error: ${e2.javaClass.name} - ${e2.message}")
                    }
                }
            }
        } catch (e: Throwable) {
            log(TAG, "forceStopApp via IActivityManager error: ${e.javaClass.name} - ${e.message}")
        }
        
        if (!success) {
            try {
                // Pre-Android 8.0 fallback
                val amClass = de.robv.android.xposed.XposedHelpers.findClass("android.app.ActivityManagerNative", context.classLoader)
                val iam = de.robv.android.xposed.XposedHelpers.callStaticMethod(amClass, "getDefault")
                if (iam != null) {
                    de.robv.android.xposed.XposedHelpers.callMethod(iam, "forceStopPackage", packageName, -2) // USER_CURRENT = -2
                    success = true
                }
            } catch (e: Throwable) {
                log(TAG, "forceStopApp via ActivityManagerNative error: ${e.javaClass.name} - ${e.message}")
            }
        }

        if (success) {
            log(TAG, "Successfully force stopped $packageName")
            Toast.makeText(context, "Force stopped $packageName", Toast.LENGTH_SHORT).show()
        } else {
            log(TAG, "System API failed, trying root shell...")
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
                process.waitFor()
                if (process.exitValue() == 0) {
                    Toast.makeText(context, "Force stopped $packageName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to force stop $packageName (exit code ${process.exitValue()})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                log(TAG, "Root force stop failed: ${e.message}")
                Toast.makeText(context, "Cannot force stop - no system access", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun getForceStopIcon(context: Context): android.graphics.drawable.Drawable? {
        // Try to use Android's built-in close icon
        val resId = context.resources.getIdentifier("ic_menu_close_clear_cancel", "drawable", "android")
        if (resId != 0) {
            return context.getDrawable(resId)
        }
        return context.getDrawable(android.R.drawable.ic_delete)
    }

    @SuppressLint("DiscouragedApi")
    private fun getForceStopLabel(context: Context): String {
        val resId = context.resources.getIdentifier("force_stop", "string", context.packageName)
        if (resId != 0) {
            return context.getString(resId)
        }
        return "Force stop"
    }

    companion object {
        private const val TAG = "ForceStopShortcut"
    }
}
