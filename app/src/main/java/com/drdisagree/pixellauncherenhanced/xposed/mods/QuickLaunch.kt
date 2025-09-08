package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.data.common.Constants.QUICK_LAUNCH
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

/**
 * QuickLaunch
 *
 * Intercepts the Enter/IME_ACTION_SEARCH key in the Pixel Launcher search field.
 * Behavior:
 *  - If the first search result corresponds to an app (direct AppInfo / wrapper / SearchTarget), launch it instantly.
 *  - Otherwise, delegate to the original TextView.OnEditorActionListener so the stock Google search (or other original behavior) occurs with zero added delay.
 *
 * Implementation notes:
 *  - No polling / deferred logic: everything happens synchronously in the IME action.
 *  - Reflection is intentionally shallow: only minimal field/method probing to extract component + user.
 *  - If extraction fails, we never consume the event, preserving native search behavior.
 */
class QuickLaunch(context: Context) : ModPack(context) {

	private data class CmpUser(val component: ComponentName, val user: UserHandle)

	private var quickLaunch = false

	private var recyclerRef: WeakReference<View>? = null
	private var containerRef: WeakReference<ViewGroup>? = null
	private var alphaListRef: WeakReference<Any>? = null // AlphabeticalAppsList instance if discovered

	override fun updatePrefs(vararg key: String) {
		XPrefs.Xprefs.apply { quickLaunch = getBoolean(QUICK_LAUNCH, false) }
	}

	override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
		// Core: hook container inflation & keep lightweight references to list / recycler / search box
		listOf(
			"com.google.android.apps.nexuslauncher.allapps.SearchContainerView", // Pixel specific
			"com.android.launcher3.allapps.SearchContainerView",
			"com.android.launcher3.allapps.ActivityAllAppsContainerView" // fallback
		).forEach { className ->
			findClass(className, suppressError = true)
				.hookMethod("onFinishInflate")
				.runAfter { param ->
					val container = param.thisObject as? ViewGroup ?: return@runAfter
					containerRef = WeakReference(container)
					cacheAlphabeticalList(container)
					cacheFromAllAppsContainer(container)
					if (recyclerRef?.get() == null) findSearchRecycler(container)?.let {
						recyclerRef = WeakReference(it)
					}
					val searchView = locateSearchEditText(container) ?: return@runAfter
					if (!quickLaunch) return@runAfter
					attachEditorListener(searchView)
				}
		}

		// Keep references when initContent is invoked (alternative construction path)
		findClass(
			"com.android.launcher3.allapps.ActivityAllAppsContainerView",
			suppressError = true
		)
			.hookMethod("initContent")
			.runAfter { p ->
				val container = p.thisObject as? ViewGroup ?: return@runAfter
				containerRef = WeakReference(container)
				runCatching { container.callMethod("getSearchResultList") }.getOrNull()?.let { if (it.javaClass.name.contains("AlphabeticalAppsList")) alphaListRef = WeakReference(it) }
				runCatching { container.callMethod("getSearchRecyclerView") }.getOrNull()
					?.let { rv -> recyclerRef = WeakReference(rv as View) }
				cacheFromAllAppsContainer(container)
			}

		// Track recycler attachment (defensive; some builds swap instances)
		listOf(
			"com.android.launcher3.allapps.SearchRecyclerView",
			"com.google.android.apps.nexuslauncher.allapps.SearchRecyclerView",
			"com.google.android.apps.nexuslauncher.allapps.GSearchRecyclerView"
		).forEach { className ->
			findClass(className, suppressError = true)
				.hookMethod("onAttachedToWindow")
				.runAfter { hook ->
					val rv = hook.thisObject as? RecyclerView ?: return@runAfter
					if (recyclerRef?.get() !== rv) recyclerRef = WeakReference(rv)
				}
		}

		// Keep alphaListRef fresh when search results mutate
		findClass(
			"com.android.launcher3.allapps.AlphabeticalAppsList",
			suppressError = true
		)
			.hookMethod("setSearchResults")
			.runAfter { param ->
				if (alphaListRef?.get() !== param.thisObject) alphaListRef = WeakReference(param.thisObject)
			}
	}

	private fun attachEditorListener(edit: EditText) {
		val previous = getExistingOnEditorActionListener(edit)
		val wrapper = TextView.OnEditorActionListener { v, actionId, event ->
			val isEnter = actionId == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
			if (!isEnter) return@OnEditorActionListener previous?.onEditorAction(v, actionId, event) ?: false
			if (!quickLaunch) return@OnEditorActionListener previous?.onEditorAction(v, actionId, event) ?: false

			val inlineInfo = fetchFirstAppInfoFromModel()
			if (inlineInfo != null && inlineInfo.isLaunchableApp()) {
				launchInfoFromAny(v.context, inlineInfo)
				return@OnEditorActionListener true
			}

			val firstWrapper = currentFirstWrapper()
			if (firstWrapper != null) {
				val directInfo = resolvePossibleInfo(firstWrapper)
				if (directInfo != null && directInfo.isLaunchableApp()) {
					launchInfoFromAny(v.context, directInfo)
					return@OnEditorActionListener true
				}
				extractFromSearchTarget(firstWrapper)?.let { cu ->
					launchDirect(v.context, cu.component, cu.user, "instantTarget")
					return@OnEditorActionListener true
				}
			}
			// Fall back to original listener (Google search) if present
			return@OnEditorActionListener previous?.onEditorAction(v, actionId, event) ?: false
		}
		// Install our wrapper
		edit.setOnEditorActionListener(wrapper)
	}

	@SuppressLint("DiscouragedPrivateApi")
	private fun getExistingOnEditorActionListener(edit: TextView): TextView.OnEditorActionListener? {
		return try {
			val tvClass = TextView::class.java
			val editorField = tvClass.getDeclaredField("mEditor").apply { isAccessible = true }
			val editorObj = editorField.get(edit) ?: return null
			val editorCls = editorObj.javaClass
			val ictField = editorCls.getDeclaredField("mInputContentType").apply { isAccessible = true }
			val ictObj = ictField.get(editorObj) ?: return null
			val ictCls = ictObj.javaClass
			val listenerField = ictCls.getDeclaredField("onEditorActionListener").apply { isAccessible = true }
			listenerField.get(ictObj) as? TextView.OnEditorActionListener
		} catch (_: Throwable) { null }
	}

	private fun currentFirstWrapper(): Any? {
		val listObj = alphaListRef?.get() ?: return null
		return try {
			(listObj.getFieldSilently("mSearchResults") as? List<*>)?.firstOrNull()
				?: (listObj.getFieldSilently("mAdapterItems") as? List<*>)?.firstOrNull()
		} catch (_: Throwable) { null }
	}

	// Enhanced extraction focusing on SearchTarget field 'a' from d1 wrapper
	private fun extractFromSearchTarget(first: Any): CmpUser? {
		try {
			val cls = first.javaClass
			val aField = cls.getDeclaredField("a")
			aField.isAccessible = true
			val searchTarget = aField.get(first)
			
			if (searchTarget != null) {
					// Try direct field access on SearchTarget
					val stClass = searchTarget.javaClass
					var foundComponent: ComponentName? = null
					var foundUser: UserHandle? = null
					var resultType: Int? = null
					var pkg: String? = null
					var extras: android.os.Bundle? = null

					// Preferred: use public API methods if present (invoke each once)
					for (m in stClass.declaredMethods) {
						if (m.parameterTypes.isNotEmpty()) continue
						try {
							m.isAccessible = true
							val r = m.invoke(searchTarget)
							when (m.name) {
								"getResultType" -> resultType = (r as? Int) ?: resultType
								"getPackageName" -> pkg = (r as? String) ?: pkg
								"getExtras" -> extras = (r as? android.os.Bundle) ?: extras
								"getUserHandle" -> foundUser = (r as? UserHandle) ?: foundUser
							}
							when (r) {
								is ComponentName -> if (foundComponent == null) foundComponent = r
								is UserHandle -> if (foundUser == null) foundUser = r
							}
						} catch (_: Throwable) {}
					}

					// Fallback: scan fields
					if (pkg == null || resultType == null || foundUser == null) {
						for (f in stClass.declaredFields) {
							try {
								f.isAccessible = true
								val v = f.get(searchTarget)
								when (v) {
									is ComponentName -> if (foundComponent == null) foundComponent = v
									is UserHandle -> if (foundUser == null) foundUser = v
									is String -> if (pkg == null && f.name.contains("Package", true)) pkg = v
									is Int -> if (resultType == null && f.name.contains("ResultType", true)) resultType = v
									is android.os.Bundle -> if (extras == null) extras = v
								}
							} catch (_: Throwable) {}
						}
					}

					// If component still missing, try to synthesize it
					val ctx = (containerRef?.get() as? View)?.context ?: (recyclerRef?.get() as? View)?.context
					if (foundComponent == null && ctx != null && pkg != null) {
						// If extras exposes explicit class (matches Pixel pattern for certain result types, e.g. 32768)
						val explicitClass = extras?.let { b ->
							try { if (b.containsKey("class")) b.getString("class") else null } catch (_: Throwable) { null }
						}
						if (!explicitClass.isNullOrBlank()) {
							foundComponent = ComponentName(pkg, explicitClass)
						}
						// Standard app result (resultType == 1) has only package; resolve launcher activity
						if (foundComponent == null && resultType == 1) {
							foundComponent = resolveMainActivityComponent(ctx, pkg)
						}
					}

					if (foundComponent != null && foundUser != null) return CmpUser(foundComponent, foundUser)
			}
		} catch (_: Throwable) {
			// ignore
		}
		return null
	}

	// Cache main activity component lookups per package to avoid repeated PM queries
	private val mainActivityCache = mutableMapOf<String, ComponentName?>()

	private fun resolveMainActivityComponent(ctx: Context, packageName: String): ComponentName? {
		mainActivityCache[packageName]?.let { return it }
		var resolved: ComponentName? = null
		try {
			// Fast path: getLaunchIntentForPackage
			val launchIntent = ctx.packageManager.getLaunchIntentForPackage(packageName)
			resolved = launchIntent?.component
			if (resolved == null) {
				// Manual query of launcher activities
				val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
				val list = ctx.packageManager.queryIntentActivities(intent, 0)
				if (list.isNotEmpty()) {
					// Prefer activity whose name equals packageName + ".MainActivity" style or first entry
					resolved = list.firstOrNull { it.activityInfo?.name?.contains("Main", true) == true }?.activityInfo?.let {
						ComponentName(it.packageName, it.name)
					} ?: list[0].activityInfo?.let { ComponentName(it.packageName, it.name) }
				}
			}
		} catch (_: Throwable) {}
		mainActivityCache[packageName] = resolved
		return resolved
	}

	private fun launchInfoFromAny(context: Context, info: Any) {
		try {
			val cmp = info.getComponentName()
			val user = info.getFieldSilently("user") as? UserHandle
			if (cmp != null && user != null) {
				val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
				la.startMainActivity(cmp, user, null, null)
			}
		} catch (_: Throwable) { }
	}

	private fun Any?.isLaunchableApp(): Boolean {
		val cmp = getComponentName()
		val user = getFieldSilently("user") as? UserHandle
		return cmp != null && user != null
	}

	private fun locateSearchEditText(root: ViewGroup): EditText? {
		val stack = ArrayDeque<View>()
		stack.add(root)
		while (stack.isNotEmpty()) {
			val v = stack.removeFirst()
			if (v is EditText) return v
			if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
		}
		return null
	}

	private fun findSearchRecycler(root: ViewGroup): RecyclerView? {
		val stack = ArrayDeque<View>()
		stack.add(root)
		while (stack.isNotEmpty()) {
			val v = stack.removeFirst()
			if (v is RecyclerView) {
				val name = v.javaClass.name
				if (name.contains("SearchRecyclerView") || name.contains("GSearchRecyclerView")) return v
			}
			if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
		}
		return null
	}

	private fun Any?.getComponentName(): ComponentName? {
		if (this == null) return null
		return getFieldSilently("componentName") as? ComponentName
			?: getFieldSilently("mComponentName") as? ComponentName
			?: try { callMethod("getTargetComponent") as? ComponentName } catch (_: Throwable) { null }
	}

	private fun launchDirect(
		context: Context,
		cmp: ComponentName,
		user: UserHandle,
		@Suppress("UNUSED_PARAMETER", "SameParameterValue") source: String
	) {
		try {
			val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
			la.startMainActivity(cmp, user, null, null)
		} catch (_: Throwable) {}
	}

	// --- Model (AlphabeticalAppsList) fallback ---
	private fun cacheAlphabeticalList(container: ViewGroup) {
		if (alphaListRef?.get() != null) return
		try {
			container.javaClass.declaredFields.forEach { f ->
				try {
					f.isAccessible = true
					val v = f.get(container) ?: return@forEach
					val n = v.javaClass.name
					if (n.contains("AlphabeticalAppsList")) {
						alphaListRef = WeakReference(v)
						return
					}
				} catch (_: Throwable) {}
			}
		} catch (_: Throwable) {}
	}

	private fun extractFromAlphabeticalList(): Any? {
		val alpha = ensureAlphabeticalList() ?: return null
		// Order: search results (since user is searching), then adapter items
		(alpha.getFieldSilently("mSearchResults") as? List<*>)?.let { sr ->
			if (sr.isNotEmpty()) {
				resolvePossibleInfo(sr[0])?.let { return it }
			}
		}
		val adapterItems = runCatching { alpha.callMethod("getAdapterItems") }.getOrNull() as? List<*>
		adapterItems?.firstOrNull()?.let { first -> resolvePossibleInfo(first)?.let { return it } }
		adapterItems?.firstOrNull { it?.getFieldSilently("itemInfo") != null }?.let { return it.getFieldSilently("itemInfo") }
		return null
	}

	private fun fetchFirstAppInfoFromModel(): Any? = extractFromAlphabeticalList()

	private fun ensureAlphabeticalList(): Any? {
		alphaListRef?.get()?.let { return it }
		// Attempt via container methods
		containerRef?.get()?.let { parent ->
			// direct getter if available
			runCatching { parent.callMethod("getSearchResultList") }.getOrNull()?.let { listObj ->
				if (listObj.javaClass.name.contains("AlphabeticalAppsList")) {
					alphaListRef = WeakReference(listObj)
					return listObj
				}
			}
			cacheFromAllAppsContainer(parent)
		}
		return alphaListRef?.get()
	}

	private fun resolvePossibleInfo(obj: Any?): Any? {
		if (obj == null) return null
		val cn = obj.javaClass.name
		if (cn.endsWith("AppInfo") || cn.endsWith("ActivityInfo")) return obj
		val fieldNames = listOf("itemInfo","mInfo","appInfo","info")
		for (fn in fieldNames) {
			val f = obj.getFieldSilently(fn)
			if (f != null) return f
		}
		return extractAppInfoDynamic(obj)
	}

	private fun extractAppInfoDynamic(root: Any?, depth: Int = 0): Any? {
		if (root == null || depth > 2) return null
		val cls = root.javaClass
		val name = cls.name
		if (name.contains("AppInfo")) return root
		for (f in cls.declaredFields) {
			try {
				f.isAccessible = true
				val v = f.get(root) ?: continue
				val vn = v.javaClass.name
				if (vn.contains("AppInfo")) return v
				val cmp = v.getComponentName()
				val user = v.getFieldSilently("user")
				if (cmp != null && user != null) return v
				if (!vn.startsWith("java.") && !vn.startsWith("android.")) {
					extractAppInfoDynamic(v, depth + 1)?.let { return it }
				}
			} catch (_: Throwable) {}
		}
		return null
	}

	private fun cacheFromAllAppsContainer(container: ViewGroup) {
		// ActivityAllAppsContainerView keeps an ArrayList mAH of AdapterHolder, each has mAppsList
		if (alphaListRef?.get() != null) return
		val clsName = container.javaClass.name
		if (!clsName.contains("ActivityAllAppsContainerView")) return
		try {
			container.getFieldSilently("mAH")?.let { ahList ->
				if (ahList is List<*>) {
					ahList.forEach { holder ->
						val listObj = holder?.getFieldSilently("mAppsList")
						if (listObj != null && listObj.javaClass.name.contains("AlphabeticalAppsList")) {
							alphaListRef = WeakReference(listObj)
							return
						}
					}
				}
			}
		} catch (_: Throwable) {}
	}
}