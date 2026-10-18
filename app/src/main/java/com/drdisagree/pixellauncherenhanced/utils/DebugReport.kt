package com.drdisagree.pixellauncherenhanced.utils

import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UserManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.data.common.Constants.BACKUP_DRAWER_LAYOUT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.BACKUP_HOME_LAYOUT
import com.drdisagree.pixellauncherenhanced.data.common.Constants.BACKUP_SETTINGS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_FOLDERS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DRAWER_TABS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.ICON_PACKS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.THEMED_ICON_PACKS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LABEL_OVERRIDES
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PINNED_SHORTCUTS_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHORTCUTS_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHORTCUTS_RESPONSE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHORTCUT_LABELS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_REQUEST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_RESPONSE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

class DebugReport(private val context: Context) {

    enum class Step { DEVICE, ROOT, LAUNCHER, RUNTIME, LAUNCHER_STATE, LOGS, SETTINGS, APK, ATTACHMENTS, PACKAGE }

    enum class Status { PENDING, RUNNING, DONE, SKIPPED, FAILED }

    data class Options(
        val includeApk: Boolean,
        val includeLogs: Boolean,
        val includeLauncherData: Boolean,
        val includeSettings: Boolean,
        val description: String,
        val attachments: List<Uri>
    )

    enum class Severity { ERROR, WARNING, INFO }

    class Finding(val severity: Severity, val title: String, val detail: String? = null)

    class Result(val file: File, val findings: List<Finding>)

    private val workDir = File(context.cacheDir, WORK_DIR)
    private val outputDir = File(context.cacheDir, OUTPUT_DIR)
    private val report = JSONObject()
    private val findings = ArrayList<Finding>()
    private val entries = LinkedHashMap<String, File>()
    private var rooted = false
    private var launcherPackage: String? = null
    private var launcherInfo: PackageInfo? = null
    private var runtime: JSONObject? = null
    private val salt = UUID.randomUUID().toString()
    private val privatePackages = HashSet<String>()
    private val publicPackages = HashSet<String>()
    private val tokens = HashMap<String, String>()

    suspend fun build(options: Options, onStep: (Step, Status, String?) -> Unit): Result = withContext(Dispatchers.IO) {
        workDir.deleteRecursively()
        workDir.mkdirs()
        outputDir.mkdirs()
        RootShell.init()
        rooted = RootShell.isRootShell()
        loadPackageVisibility()
        try {
            collectAll(options, onStep)
        } finally {
            workDir.deleteRecursively()
            RPrefs.clearPrefs(DEBUG_REPORT_REQUEST, DEBUG_REPORT_RESPONSE)
        }
    }

    private suspend fun collectAll(options: Options, onStep: (Step, Status, String?) -> Unit): Result {

        step(Step.DEVICE, onStep) { collectDevice() }
        step(Step.ROOT, onStep) { if (rooted) collectRoot() else skip("Root access not granted") }
        step(Step.LAUNCHER, onStep) { collectLauncher() }
        step(Step.RUNTIME, onStep) { collectRuntime() }
        step(Step.LAUNCHER_STATE, onStep) {
            when {
                !rooted -> skip("Needs root")
                launcherPackage == null -> skip("No supported launcher installed")
                else -> collectLauncherState(options.includeLauncherData)
            }
        }
        step(Step.LOGS, onStep) { if (options.includeLogs) collectLogs() else skip("Turned off") }
        step(Step.SETTINGS, onStep) { if (options.includeSettings) collectSettings() else skip("Turned off") }
        step(Step.APK, onStep) { if (options.includeApk) collectApk() else skip("Turned off") }
        step(Step.ATTACHMENTS, onStep) {
            if (options.description.isBlank() && options.attachments.isEmpty()) skip("Nothing added")
            else collectUserInput(options)
        }

        var output: File? = null
        step(Step.PACKAGE, onStep) {
            val archive = writeArchive()
            output = archive
            pruneReports()
            "${archive.name}, ${archive.length() / KB} KB"
        }
        return Result(output ?: throw IllegalStateException("Report could not be written"), findings.toList())
    }

    private fun pruneReports() {
        outputDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEPT_REPORTS)?.forEach { it.delete() }
    }

    private class Skipped(message: String) : Exception(message)

    private fun skip(reason: String): Nothing = throw Skipped(reason)

    private suspend fun step(step: Step, onStep: (Step, Status, String?) -> Unit, block: suspend () -> String?) {
        withContext(Dispatchers.Main) { onStep(step, Status.RUNNING, null) }
        val startedAt = SystemClock.elapsedRealtime()
        val (status, detail) = try {
            Status.DONE to block()
        } catch (skipped: Skipped) {
            Status.SKIPPED to skipped.message
        } catch (throwable: Throwable) {
            report.optJSONObject("stepErrors") ?: JSONObject().also { report.put("stepErrors", it) }
            report.getJSONObject("stepErrors").put(step.name, throwable.stackTraceToString())
            Status.FAILED to (throwable.message ?: throwable.javaClass.simpleName)
        }
        report.optJSONObject("steps") ?: JSONObject().also { report.put("steps", it) }
        report.getJSONObject("steps").put(
            step.name,
            JSONObject().put("status", status.name).put("detail", detail).put("ms", SystemClock.elapsedRealtime() - startedAt)
        )
        withContext(Dispatchers.Main) { onStep(step, status, detail) }
    }

    private fun collectDevice(): String {
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val windowManager = context.getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val userManager = context.getSystemService(UserManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val data = StatFs(Environment.getDataDirectory().path)

        report.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
            put("socManufacturer", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else null)
            put("socModel", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("ramTotalMb", memory.totalMem / MB)
            put("ramAvailableMb", memory.availMem / MB)
            put("lowRam", activityManager.isLowRamDevice)
            put("dataFreeMb", data.availableBytes / MB)
            put("dataTotalMb", data.totalBytes / MB)
        })
        report.put("system", JSONObject().apply {
            put("release", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("sdkFull", if (Build.VERSION.SDK_INT >= 36) Build.VERSION.SDK_INT_FULL else null)
            put("previewSdk", Build.VERSION.PREVIEW_SDK_INT)
            put("codename", Build.VERSION.CODENAME)
            put("securityPatch", Build.VERSION.SECURITY_PATCH)
            put("fingerprint", Build.FINGERPRINT)
            put("display", Build.DISPLAY)
            put("id", Build.ID)
            put("incremental", Build.VERSION.INCREMENTAL)
            put("type", Build.TYPE)
            put("tags", Build.TAGS)
            put("kernel", System.getProperty("os.version"))
            put("vm", System.getProperty("java.vm.version"))
            put("uptimeMinutes", SystemClock.elapsedRealtime() / 60000)
            put("timezone", TimeZone.getDefault().id)
        })
        report.put("display", JSONObject().apply {
            put("widthPx", bounds.width())
            put("heightPx", bounds.height())
            put("densityDpi", metrics.densityDpi)
            put("density", metrics.density)
            put("fontScale", configuration.fontScale)
            put("smallestWidthDp", configuration.smallestScreenWidthDp)
            put("screenWidthDp", configuration.screenWidthDp)
            put("screenHeightDp", configuration.screenHeightDp)
            put("orientation", configuration.orientation)
            put("nightMode", (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            put("locales", configuration.locales.toLanguageTags())
            put("layoutDirection", configuration.layoutDirection)
        })
        report.put("userEnvironment", JSONObject().apply {
            put("userId", Process.myUserHandle().hashCode())
            put("profiles", userManager.userProfiles.size)
            put("powerSave", power.isPowerSaveMode)
            put("navigationMode", secureInt("navigation_mode"))
            put("animatorScale", Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE))
            put("transitionScale", Settings.Global.getString(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE))
            val liveWallpaper = runCatching { WallpaperManager.getInstance(context).wallpaperInfo }.getOrNull()
            put("liveWallpaper", liveWallpaper?.component?.flattenToString())
            put("liveWallpaperSystem", liveWallpaper?.let { it.packageName in publicPackages })
            put("themeOverlays", Settings.Secure.getString(context.contentResolver, "theme_customization_overlay_packages"))
        })
        report.put("module", JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("buildType", BuildConfig.BUILD_TYPE)
            put("installer", runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull())
        })
        writeText("system/getprop.txt", sanitize(redactProps(runCommand("getprop", rooted).lines().filter { PROP_KEEP.containsMatchIn(it.substringBefore("]:")) }.joinToString("\n"))))
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    }

    private fun collectRoot(): String {
        val script = listOf(
            "echo '# id'; id",
            "echo '# su'; su -v; su -V",
            "echo '# magisk'; magisk -v; magisk -V; magisk --sqlite 'SELECT key,value FROM settings'",
            "echo '# kernelsu'; ksud -V; ls -la /data/adb/ksu",
            "echo '# apatch'; apd -V; ls -la /data/adb/ap",
            "echo '# selinux'; getenforce",
            "echo '# adb'; ls -la /data/adb",
            "echo '# lspd'; ls -la /data/adb/lspd /data/adb/lspd/log /data/adb/lspd/config",
            "echo '# nsenter'; which nsenter"
        ).joinToString("; ") { "$it 2>&1" }
        writeText("root/environment.txt", runCommand(script, true))
        val modules = runCommand(
            "for d in /data/adb/modules/*; do echo \"== \$d\"; cat \"\$d/module.prop\" 2>&1; " +
                    "for f in disable remove update skip_mount; do [ -e \"\$d/\$f\" ] && echo \"flag=\$f\"; done; done",
            true
        )
        writeText("root/modules.txt", modules)
        val names = Regex("^name=(.*)$", RegexOption.MULTILINE).findAll(modules).map { it.groupValues[1].trim() }.toList()
        report.put("rootModules", JSONArray(names))
        val selinux = runCommand("getenforce", true).trim()
        report.put("selinux", selinux)
        return "${names.size} modules, SELinux $selinux"
    }

    private fun collectLauncher(): String {
        val packageManager = context.packageManager
        val home = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        val homeApps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }.distinct()
        val supported = listOf(PIXEL_LAUNCHER_PACKAGE, LAUNCHER3_PACKAGE).mapNotNull { packageName ->
            runCatching { packageInfo(packageName) }.getOrNull()
        }
        val target = supported.firstOrNull { it.packageName == home } ?: supported.firstOrNull()
        launcherPackage = target?.packageName
        launcherInfo = target

        report.put("launchers", JSONObject().apply {
            put("defaultHome", home)
            put("homeApps", JSONArray(homeApps))
            put("target", launcherPackage)
            put("installed", JSONArray(supported.map { describePackage(it) }))
        })
        if (target == null) {
            findings.add(Finding(Severity.ERROR, "No supported launcher is installed"))
        } else if (home != target.packageName) {
            findings.add(Finding(Severity.WARNING, "The launcher is not the default home app", "Default home: $home"))
        }
        return target?.let { "${it.packageName} ${it.versionName}" } ?: "No supported launcher"
    }

    private suspend fun collectRuntime(): String {
        val requestId = UUID.randomUUID().toString()
        val response = withTimeoutOrNull(RUNTIME_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SharedPreferences.OnSharedPreferenceChangeListener {
                    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
                        if (key != DEBUG_REPORT_RESPONSE) return
                        val decoded = runCatching { decode(RPrefs.getString(DEBUG_REPORT_RESPONSE).orEmpty()) }.getOrNull()
                        if (decoded?.optString("requestId") != requestId) return
                        if (launcherPackage != null && decoded.optString("package") != launcherPackage) return
                        RPrefs.unregisterOnSharedPreferenceChangeListener(this)
                        if (continuation.isActive) continuation.resume(decoded)
                    }
                }
                RPrefs.registerOnSharedPreferenceChangeListener(listener)
                continuation.invokeOnCancellation { RPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
                RPrefs.putString(DEBUG_REPORT_REQUEST, requestId)
            }
        }
        RPrefs.clearPrefs(DEBUG_REPORT_REQUEST, DEBUG_REPORT_RESPONSE)

        if (response == null) {
            findings.add(
                Finding(
                    Severity.ERROR,
                    "The module is not running in the launcher",
                    "No answer from ${launcherPackage ?: "the launcher"}. Check the LSPosed scope, then restart the launcher"
                )
            )
            report.put("runtime", JSONObject.NULL)
            skip("No answer from the launcher")
        }
        runtime = response
        writeText("launcher/runtime.json", response.toString(2))
        modSections(response).forEach { (owner, section) -> writeText("launcher/mods/$owner.txt", section) }
        analyzeRuntime(response)
        val hooks = response.optJSONArray("hooks")?.length() ?: 0
        return "${response.optJSONArray("mods")?.length() ?: 0} mods, $hooks hooks"
    }

    private fun analyzeRuntime(runtime: JSONObject) {
        val module = runtime.optJSONObject("module")
        if (module != null && module.optLong("versionCode") != BuildConfig.VERSION_CODE.toLong()) {
            findings.add(
                Finding(
                    Severity.ERROR,
                    "The launcher runs an older module version",
                    "Launcher: ${module.optString("versionName")} (${module.optLong("versionCode")}), app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Restart the launcher"
                )
            )
        }
        val probes = runtime.optJSONObject("probes")
        if ((probes?.optString("bootLoopStrikes")?.toIntOrNull() ?: 0) >= 3) {
            findings.add(Finding(Severity.ERROR, "Boot loop protection turned the module off"))
        }
        val mods = runtime.optJSONArray("mods")?.objects().orEmpty()
        val failedMods = mods.filter { !it.isNull("loadError") }
        if (failedMods.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.ERROR,
                    "${failedMods.size} ${plural(failedMods.size, "mod", "mods")} failed to load",
                    failedMods.joinToString("\n") { "${it.optString("name")}: ${it.optString("loadError")}" }
                )
            )
        }
        val settingsFailures = mods.filter { !it.isNull("prefsError") }
        if (settingsFailures.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.WARNING,
                    "${settingsFailures.size} ${plural(settingsFailures.size, "mod", "mods")} could not read settings",
                    settingsFailures.joinToString("\n") { "${it.optString("name")}: ${it.optString("prefsError")}" }
                )
            )
        }
        val missingClasses = runtime.optJSONArray("classLookups")?.objects()
            ?.filter { it.isNull("resolved") && !it.optBoolean("optional") }.orEmpty()
        if (missingClasses.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.WARNING,
                    "${missingClasses.size} required ${plural(missingClasses.size, "class is", "classes are")} missing",
                    missingClasses.joinToString("\n") { lookup ->
                        "${lookup.optString("owner")}: ${lookup.optJSONArray("candidates")?.strings()?.joinToString(" | ")}"
                    }
                )
            )
        }
        val health = runtime.optJSONArray("health")?.objects().orEmpty()
        val failedHealth = health.filter { it.opt("result") == false }
        if (failedHealth.isNotEmpty()) {
            findings.add(
                0,
                Finding(
                    Severity.ERROR,
                    "${failedHealth.size} health ${plural(failedHealth.size, "check", "checks")} failed",
                    failedHealth.joinToString("\n") { "${it.optString("owner")}: ${it.optString("name")}" }
                )
            )
        }
        val brokenHealth = health.filter { !it.isNull("error") }
        if (brokenHealth.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.WARNING,
                    "${brokenHealth.size} health ${plural(brokenHealth.size, "check", "checks")} could not run",
                    brokenHealth.joinToString("\n") { "${it.optString("owner")}: ${it.optString("name")}: ${it.optString("error")}" }
                )
            )
        }
        val hooks = runtime.optJSONArray("hooks")?.objects().orEmpty()
        val workingCapabilities = workingCapabilities(hooks)
        val missingHooks = hooks.filter {
            (it.optJSONArray("resolved")?.length() ?: 0) == 0 && !it.isNull("class") && !it.optBoolean("suppressed") &&
                    !isCovered(it, workingCapabilities)
        }
        if (missingHooks.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.WARNING,
                    "${missingHooks.size} required ${plural(missingHooks.size, "hook", "hooks")} found no method and have no working fallback",
                    missingHooks.joinToString("\n") { hook ->
                        "${hook.optString("owner")}: ${hook.optString("class").substringAfterLast('.')}.${hook.optJSONArray("requested")?.strings()?.joinToString("|")}"
                    }
                )
            )
        }
        val uncaptured = uncapturedObjects(runtime)
        if (uncaptured.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.INFO,
                    "${uncaptured.size} captured ${plural(uncaptured.size, "object is", "objects are")} still empty",
                    uncaptured.joinToString("\n") + "\nSome are captured only after their screen opens"
                )
            )
        }
        val errors = runtime.optJSONArray("errors")?.objects().orEmpty()
        if (errors.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.WARNING,
                    "${errors.size} ${plural(errors.size, "error was", "errors were")} caught in the launcher",
                    errors.groupBy { it.optString("owner") }.entries.joinToString("\n") { (owner, list) -> "$owner: ${list.size}" }
                )
            )
        }
        if (probes?.optString("rootProxyConnected") == "false") {
            findings.add(Finding(Severity.WARNING, "The launcher cannot reach the root service", "Grant root to PLEnhanced"))
        }
        probes?.optString("wallpaperGlass")?.takeIf { it.contains("available=false") && it.contains("checked=true") }?.let {
            findings.add(Finding(Severity.WARNING, "Wallpaper glass is unavailable", it))
        }
        val misses = runtime.optJSONArray("memberMisses")?.objects().orEmpty()
        if (misses.isNotEmpty()) {
            findings.add(
                Finding(
                    Severity.INFO,
                    "${misses.size} runtime ${plural(misses.size, "lookup", "lookups")} missed (version differences, fine unless a health check fails)",
                    misses.groupBy { it.optString("owner") }.entries.joinToString("\n") { (owner, list) -> "$owner: ${list.size}" }
                )
            )
        }
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many

    private fun capabilities(hook: JSONObject): List<String> {
        if (hook.isNull("capability")) return emptyList()
        return hook.optString("capability").split(',').map { "${hook.optString("owner")}|${it.trim()}" }.filter { it.isNotBlank() }
    }

    private fun workingCapabilities(hooks: List<JSONObject>): Map<String, JSONObject> {
        val working = HashMap<String, JSONObject>()
        hooks.filter { (it.optJSONArray("resolved")?.length() ?: 0) > 0 }.forEach { hook ->
            capabilities(hook).forEach { capability ->
                val current = working[capability]
                if (current == null || hook.optLong("fired") > current.optLong("fired")) working[capability] = hook
            }
        }
        return working
    }

    private fun isCovered(hook: JSONObject, working: Map<String, JSONObject>): Boolean {
        val capabilities = capabilities(hook)
        return capabilities.isNotEmpty() && capabilities.all { it in working }
    }

    private fun coveringHook(hook: JSONObject, working: Map<String, JSONObject>): String? {
        val capabilities = capabilities(hook)
        if (capabilities.isEmpty() || !capabilities.all { it in working }) return null
        return capabilities.mapNotNull { working[it] }.distinct().joinToString(", ") { cover ->
            "${cover.optString("class").substringAfterLast('.')}.${cover.optJSONArray("requested")?.strings()?.joinToString("|")}"
        }
    }

    private fun uncapturedObjects(runtime: JSONObject): List<String> {
        val states = runtime.optJSONObject("modState") ?: return emptyList()
        val notNeeded = runtime.optJSONArray("captures")?.objects().orEmpty()
            .filter { !it.optBoolean("needed", true) }
            .map { "${it.optString("owner")}.${it.optString("field")}" }
            .toSet()
        val result = ArrayList<String>()
        states.keys().forEach { owner ->
            val state = states.optJSONObject(owner) ?: return@forEach
            state.keys().forEach { field ->
                if (!field.endsWith("Instance") && !field.endsWith("Ref")) return@forEach
                val value = state.opt(field)
                if ("$owner.$field" in notNeeded) return@forEach
                if (value == JSONObject.NULL || value == null || value.toString() == "ref(null)") result.add("$owner.$field")
            }
        }
        return result
    }

    private fun collectLauncherState(includeData: Boolean): String {
        val packageName = launcherPackage!!
        rootToFile("launcher/dumpsys_activity.txt", "dumpsys activity $packageName") { trimLauncherDump(it) }
        rootToFile("launcher/dumpsys_package.txt", "dumpsys package $packageName") { text ->
            text.lines().filter { PACKAGE_FIELDS.containsMatchIn(it) }.distinct().joinToString("\n")
        }
        rootToFile("launcher/dumpsys_wallpaper.txt", "dumpsys wallpaper")
        rootToFile("launcher/device_config.txt", "device_config list launcher")
        rootToFile("launcher/aconfig_flags.txt", "aflags list 2>&1 | grep -E 'com\\.android\\.launcher3'")
        rootToFile("launcher/overlays.txt", "cmd overlay list") { trimOverlays(it, packageName) }
        rootToFile("system/display.txt", "wm size; wm density")
        rootToFile(
            "system/settings.txt",
            "echo '# global'; settings list global | grep -iE '$GLOBAL_SETTINGS'; " +
                    "echo '# secure'; settings list secure | grep -iE '$SECURE_SETTINGS'; " +
                    "echo '# system'; settings list system | grep -iE '$SYSTEM_SETTINGS'"
        )
        collectCrashes(packageName)
        if (includeData) collectLauncherData(packageName)
        return if (includeData) "Dumps, crashes and home layout" else "Dumps and crashes"
    }

    private fun trimLauncherDump(text: String): String {
        val kept = ArrayList<String>()
        val loaderEvents = ArrayList<String>()
        var keepSection = false
        var inDeviceProfile = false
        text.lines().forEach { line ->
            val header = DUMP_HEADER.matchEntire(line)?.groupValues?.get(1)?.trim()
            when {
                header != null -> {
                    keepSection = DUMP_SECTIONS.any { header.startsWith(it) }
                    inDeviceProfile = header == "DeviceProfile"
                    if (keepSection) kept.add(line)
                }
                DUMP_EVENT.containsMatchIn(line) -> loaderEvents.add(line)
                inDeviceProfile -> if (line.trimStart().isEmpty() || line.startsWith("\t") || line.startsWith("    \t")) kept.add(line) else inDeviceProfile = false
                keepSection -> kept.add(line)
            }
        }
        if (loaderEvents.isNotEmpty()) {
            kept.add("")
            kept.add("Recent loader events (${loaderEvents.size} total, last $MAX_LOADER_EVENTS):")
            loaderEvents.distinct().takeLast(MAX_LOADER_EVENTS).forEach { kept.add(it) }
        }
        return kept.joinToString("\n")
    }

    private fun trimOverlays(text: String, packageName: String): String {
        val targets = setOf("android", "com.android.systemui", packageName)
        val kept = ArrayList<String>()
        var target: String? = null
        var listed = false
        text.lines().forEach { line ->
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("[")) {
                target = line.trim()
                listed = false
                return@forEach
            }
            if (target in targets && line.trimStart().startsWith("[x]")) {
                if (!listed) {
                    kept.add(target.orEmpty())
                    listed = true
                }
                kept.add(line)
            }
        }
        return kept.joinToString("\n")
    }

    private fun collectCrashes(packageName: String) {
        val dropbox = File(workDir, "raw/dropbox.txt")
        rootToFile(dropbox, "dumpsys dropbox --print system_app_crash system_app_anr data_app_crash data_app_anr system_app_native_crash SYSTEM_TOMBSTONE")
        if (dropbox.exists()) {
            val relevant = dropbox.readText().split(DROPBOX_SEPARATOR)
                .filter { it.contains(packageName) || it.contains(BuildConfig.APPLICATION_ID) }
                .takeLast(MAX_DROPBOX_ENTRIES)
                .map { entry -> entry.lines().take(MAX_ENTRY_LINES).joinToString("\n") }
            if (relevant.isNotEmpty()) writeText("crashes/dropbox.txt", sanitize(relevant.joinToString(DROPBOX_SEPARATOR)))
            dropbox.delete()
        }
        val anrs = runCommand(
            "ls -t /data/anr/anr_* 2>/dev/null | while read f; do grep -l '$packageName' \"\$f\"; done | head -n $MAX_CRASH_FILES",
            true
        ).lines().filter { it.isNotBlank() }
        anrs.forEach { path ->
            rootToFile("crashes/anr/${path.substringAfterLast('/')}.txt", "cat '$path'") { trimAnr(it) }
        }
        val tombstones = runCommand(
            "ls -t /data/tombstones/tombstone_* 2>/dev/null | grep -v '\\.pb$' | while read f; do " +
                    "grep -q \"^Cmdline: .*$packageName\" \"\$f\" && echo \"\$f\"; done | head -n $MAX_CRASH_FILES",
            true
        ).lines().filter { it.isNotBlank() }
        tombstones.forEach { path ->
            rootToFile("crashes/tombstones/${path.substringAfterLast('/')}.txt", "cat '$path'") { trimTombstone(it) }
        }
    }

    private fun trimAnr(text: String): String {
        val blocks = text.split(Regex("\\n(?=\")"))
        val header = blocks.first()
        val threads = blocks.drop(1).filter { block ->
            block.startsWith("\"main\"") || MODULE_FRAMES.containsMatchIn(block)
        }
        return (listOf(header) + threads).joinToString("\n")
    }

    private fun trimTombstone(text: String): String {
        val lines = text.lines()
        val end = lines.indexOfFirst { it.startsWith("memory near") || it.startsWith("--- --- ---") }
            .takeIf { it > 0 } ?: minOf(lines.size, MAX_TOMBSTONE_LINES)
        return lines.take(end).joinToString("\n")
    }

    private fun collectLauncherData(packageName: String) {
        val raw = File(workDir, "raw/launcher")
        raw.mkdirs()
        Shell.cmd(
            "for d in /data/data/$packageName /data/user_de/0/$packageName; do " +
                    "for f in \$d/databases/*.db \$d/shared_prefs/*.xml; do [ -f \"\$f\" ] && cp \"\$f\" '${raw.absolutePath}/'; done; done; " +
                    "chown -R ${Process.myUid()}:${Process.myUid()} '${raw.absolutePath}'; " +
                    "chcon -R \"\$(stat -c %C '${workDir.absolutePath}')\" '${raw.absolutePath}'"
        ).exec()
        val layout = JSONObject()
        raw.listFiles()?.filter { it.name.endsWith(".db") }?.forEach { database ->
            runCatching { layout.put(database.name, exportLayout(database)) }
                .onFailure { layout.put(database.name, JSONObject().put("error", it.toString())) }
        }
        if (layout.length() > 0) writeText("launcher/home_layout.json", layout.toString(2))
        raw.listFiles()
            ?.filter { it.name.endsWith(".xml") && !it.name.startsWith("preview-") && !it.name.startsWith("ple_") }
            ?.forEach { prefs ->
                writeText("launcher/shared_prefs/${prefs.name}", sanitize(prefs.readText(), keyValues = false))
            }
        raw.deleteRecursively()
    }

    private fun exportLayout(database: File): JSONObject {
        val result = JSONObject()
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
            }
            result.put("tables", JSONObject().apply {
                tables.forEach { table ->
                    put(table, runCatching { DatabaseUtils.queryNumEntries(db, table) }.getOrDefault(-1L))
                }
            })
            tables.filter { it == "favorites" || it.startsWith("favorites_") }.forEach { table ->
                val rows = JSONArray()
                db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                    val columns = cursor.columnNames.filter { it in LAYOUT_COLUMNS || it == "intent" }
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        columns.forEach { column ->
                            val index = cursor.getColumnIndex(column)
                            if (cursor.isNull(index)) return@forEach
                            if (column == "intent") {
                                val intent = runCatching { Intent.parseUri(cursor.getString(index), 0) }.getOrNull()
                                row.put("component", intent?.component?.flattenToShortString())
                                val target = intent?.`package` ?: intent?.component?.packageName
                                row.put("package", target)
                                row.put("systemApp", target?.let { it in publicPackages })
                                row.put("hasData", intent?.data != null)
                            } else {
                                row.put(column, cursor.getString(index))
                            }
                        }
                        rows.put(row)
                    }
                }
                result.put(table, rows)
            }
        }
        return result
    }

    private fun collectLogs(): String {
        if (!rooted) {
            writeText("logs/logcat_app.txt", sanitize(runCommand("logcat -d -v threadtime", false)))
            return "Only this app's log (needs root for more)"
        }
        val launcher = launcherPackage
        val launcherPids = launcher?.let { runCommand("pidof $it", true).split(Regex("\\s+")).filter { pid -> pid.isNotBlank() } }.orEmpty()
        launcherPids.forEach { pid ->
            rootToFile("logs/logcat_launcher_$pid.txt", "logcat -d -b main,system,crash -v threadtime --pid=$pid")
        }
        val ignoredPids = launcherPids.toSet() + Process.myPid().toString()
        val processEvents = Regex("(?i)(died|crash|killing|start proc|ANR in|force stop)")
        rootToFile(
            "logs/logcat_related.txt",
            "logcat -d -b main,system,crash -v threadtime | grep -iE '$RELATED_TAGS${launcher?.let { "|$it" }.orEmpty()}'"
        ) { text ->
            text.lines().filter { line ->
                line.split(Regex("\\s+")).getOrNull(2) !in ignoredPids &&
                        (RELATED_LOG.containsMatchIn(line) || (launcher != null && line.contains(launcher) && processEvents.containsMatchIn(line)))
            }.joinToString("\n")
        }
        val modulePattern = listOfNotNull(launcher, BuildConfig.APPLICATION_ID, "PLEnhanced").joinToString("|")
        rootToFile(
            "logs/lsposed.txt",
            "for f in /data/adb/lspd/log/modules_*.log /data/adb/lspd/log/verbose_*.log /data/adb/lspd/log.old/modules_*.log; do " +
                    "[ -f \"\$f\" ] || continue; " +
                    "echo \"== \$(basename \$f): warnings and errors\"; " +
                    "awk -v pat='${modulePattern.lowercase()}' '{ l = tolower(\$0) } " +
                    "l ~ pat && \$0 ~ / [EWF]\\// { p = 1; print; next } " +
                    "p && (\$0 ~ /^[ \\t]/ || \$0 ~ /^Caused by/ || \$0 !~ /^\\[/) { print; next } { p = 0 }' \"\$f\" | tail -n $MAX_LSPOSED_PROBLEMS; " +
                    "echo \"== \$(basename \$f): recent\"; grep -iE '$modulePattern' \"\$f\" | tail -n $MAX_LSPOSED_LINES; done"
        )
        return "${launcherPids.size} launcher processes, LSPosed log"
    }

    private fun collectSettings(): String {
        val settings = JSONObject()
        RPrefs.all.toSortedMap().forEach { (key, value) ->
            if (key in EXCLUDED_SETTINGS || EXCLUDED_SETTING_PREFIXES.any { key.startsWith(it) }) return@forEach
            settings.put(key, when {
                key in PRIVATE_SETTINGS -> "<redacted, ${value.toString().length} chars>"
                value is Set<*> -> JSONArray(value.map { anonymize(it.toString(), strict = true) })
                value is String -> anonymize(value, strict = true)
                else -> value
            })
        }
        writeText("module/settings.json", settings.toString(2))
        return "${settings.length()} settings"
    }

    private fun collectApk(): String {
        val info = launcherInfo ?: skip("No supported launcher installed")
        val application = info.applicationInfo ?: skip("No application info")
        val files = listOf(application.sourceDir) + application.splitSourceDirs.orEmpty()
        var total = 0L
        files.forEach { path ->
            val source = File(path)
            entries["launcher/apk/${source.name}"] = source
            total += source.length()
        }
        return "${files.size} files, ${total / MB} MB"
    }

    private fun collectUserInput(options: Options): String {
        if (options.description.isNotBlank()) writeText("user/description.txt", options.description.trim())
        options.attachments.forEachIndexed { index, uri ->
            val name = displayName(uri) ?: "attachment_$index"
            val target = File(workDir, "user/attachments/${index + 1}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            entries["user/attachments/${target.name}"] = target
        }
        return "${options.attachments.size} attachments"
    }

    private fun writeArchive(): File {
        report.put("generatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        report.put("rooted", rooted)
        report.put("findings", JSONArray(findings.map {
            JSONObject().put("severity", it.severity.name).put("title", it.title).put("detail", it.detail)
        }))
        writeText("report.json", report.toString(2))
        writeText("report.txt", summary())

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "")
        val output = File(outputDir, "PLEnhanced-debug-$model-$stamp.zip")
        val ordered = entries.entries.sortedBy { (name, _) -> if (name == "report.txt") 0 else if (name.startsWith("launcher/apk")) 2 else 1 }
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            ordered.forEach { (name, file) ->
                if (!file.exists()) return@forEach
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return output
    }

    private fun summary(): String = buildString {
        appendLine("PLEnhanced debug report")
        appendLine("Generated: ${report.optString("generatedAt")}")
        appendLine()
        appendLine("== Findings")
        if (findings.isEmpty()) appendLine("No problems detected automatically")
        findings.forEach { finding ->
            appendLine("[${finding.severity}] ${finding.title}")
            finding.detail?.lines()?.forEach { appendLine("    $it") }
        }
        appendLine()
        val device = report.optJSONObject("device")
        val system = report.optJSONObject("system")
        appendLine("== Environment")
        appendLine("Device: ${device?.optString("manufacturer")} ${device?.optString("model")} (${device?.optString("device")})")
        appendLine("Android: ${system?.optString("release")} (SDK ${system?.optInt("sdk")}), patch ${system?.optString("securityPatch")}")
        appendLine("Build: ${system?.optString("fingerprint")}")
        appendLine("ROM: ${system?.optString("display")}")
        appendLine("Root: ${if (rooted) "granted" else "not granted"}, SELinux ${report.optString("selinux", "unknown")}")
        appendLine("Root modules: ${report.optJSONArray("rootModules")?.strings()?.joinToString().orEmpty()}")
        appendLine("Module: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        val launchers = report.optJSONObject("launchers")
        appendLine("Launcher: ${launchers?.optString("target")} ${launcherInfo?.versionName} (${launcherInfo?.longVersionCode}), default home ${launchers?.optString("defaultHome")}")
        runtime?.let { runtime ->
            val xposed = runtime.optJSONObject("xposed")
            appendLine("Xposed API: ${xposed?.optString("apiVersion")}")
            appendLine()
            appendLine("== Probes")
            runtime.optJSONObject("probes")?.let { probes -> probes.keys().forEach { appendLine("$it: ${probes.optString(it)}") } }
            appendLine()
            modSections(runtime).forEach { (_, section) ->
                append(section)
                appendLine()
            }
        }
        appendLine("== Steps")
        report.optJSONObject("steps")?.let { steps ->
            steps.keys().forEach { key ->
                val step = steps.getJSONObject(key)
                appendLine("$key: ${step.optString("status")} ${step.optString("detail")} (${step.optLong("ms")} ms)")
            }
        }
        appendLine()
        appendLine("== Files")
        entries.keys.sorted().forEach { appendLine(it) }
    }

    private fun modSections(runtime: JSONObject): Map<String, String> {
        val mods = runtime.optJSONArray("mods")?.objects().orEmpty()
        val states = runtime.optJSONObject("modState")
        val lookups = runtime.optJSONArray("classLookups")?.objects().orEmpty()
        val hooks = runtime.optJSONArray("hooks")?.objects().orEmpty()
        val misses = runtime.optJSONArray("memberMisses")?.objects().orEmpty()
        val fallbacks = runtime.optJSONArray("fieldFallbacks")?.objects().orEmpty()
        val errors = runtime.optJSONArray("errors")?.objects().orEmpty()
        val health = runtime.optJSONArray("health")?.objects().orEmpty()
        val working = workingCapabilities(hooks)
        val known = mods.map { it.optString("name") }
        fun ownerOf(record: JSONObject): String = record.optString("owner").takeIf {
            !record.isNull("owner") && it.isNotEmpty() && it in known
        } ?: SHARED_OWNER
        val owners = known + listOf(SHARED_OWNER)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)

        return owners.associateWith { owner ->
            buildString {
                appendLine("== $owner")
                mods.firstOrNull { it.optString("name") == owner }?.let { mod ->
                    val loadError = mod.optString("loadError").takeIf { !mod.isNull("loadError") }
                    val prefsError = mod.optString("prefsError").takeIf { !mod.isNull("prefsError") }
                    appendLine("Load: ${mod.optLong("loadMs")} ms${loadError?.let { ", FAILED $it" }.orEmpty()}${prefsError?.let { ", settings error $it" }.orEmpty()}")
                }
                states?.optJSONObject(owner)?.let { state ->
                    appendLine("State:")
                    state.keys().asSequence().sorted().forEach { appendLine("  $it = ${state.opt(it)}") }
                }
                lookups.filter { ownerOf(it) == owner }.takeIf { it.isNotEmpty() }?.let { list ->
                    appendLine("Classes:")
                    list.forEach { lookup ->
                        val candidates = lookup.optJSONArray("candidates")?.strings().orEmpty()
                        val resolved = lookup.optString("resolved").takeIf { !lookup.isNull("resolved") }
                        val state = when {
                            resolved == null -> if (lookup.optBoolean("optional")) "MISSING, optional" else "MISSING"
                            lookup.optBoolean("fallback") -> "FALLBACK -> $resolved"
                            else -> "ok"
                        }
                        appendLine("  [$state] ${candidates.joinToString(" | ")}")
                    }
                }
                health.filter { ownerOf(it) == owner }.takeIf { it.isNotEmpty() }?.let { list ->
                    appendLine("Health:")
                    list.forEach { check ->
                        val state = when {
                            !check.isNull("error") -> "ERROR ${check.optString("error")}"
                            check.opt("result") == true -> "ok"
                            check.opt("result") == false -> "FAILED"
                            else -> "not in use"
                        }
                        appendLine("  [$state] ${check.optString("name")}")
                    }
                }
                hooks.filter { ownerOf(it) == owner }.takeIf { it.isNotEmpty() }?.let { list ->
                    appendLine("Hooks:")
                    list.forEach { hook ->
                        val resolved = hook.optJSONArray("resolved")?.strings().orEmpty()
                        val requested = hook.optJSONArray("requested")?.strings().orEmpty()
                        val cover = coveringHook(hook, working)
                        val state = when {
                            resolved.isEmpty() && cover != null -> "MISSING, covered by $cover"
                            resolved.isEmpty() -> if (hook.optBoolean("suppressed")) "MISSING, optional" else "MISSING"
                            requested.size > 1 && resolved.size < requested.size -> "PARTIAL"
                            hook.optLong("fired") == 0L -> "never fired"
                            else -> "fired ${hook.optLong("fired")}"
                        }
                        appendLine("  [$state] ${hook.optString("class")}.${requested.joinToString("|")} (${hook.optString("kind")}) -> ${resolved.joinToString().ifEmpty { "-" }}")
                    }
                }
                val ownMisses = misses.filter { ownerOf(it) == owner }
                val ownFallbacks = fallbacks.filter { ownerOf(it) == owner }
                if (ownMisses.isNotEmpty() || ownFallbacks.isNotEmpty()) {
                    appendLine("Runtime lookups:")
                    ownMisses.forEach { miss ->
                        appendLine("  [${miss.optString("kind")}] ${miss.optString("class")}.${miss.optJSONArray("names")?.strings()?.joinToString("|")} x${miss.optLong("count")}")
                    }
                    ownFallbacks.forEach { fallback ->
                        appendLine("  [fallback] ${fallback.optString("class")}: ${fallback.optJSONArray("candidates")?.strings()?.joinToString("|")} -> ${fallback.optString("resolved")}")
                    }
                }
                errors.filter { ownerOf(it) == owner }.takeIf { it.isNotEmpty() }?.let { list ->
                    appendLine("Errors:")
                    list.forEach { error ->
                        appendLine("  ${time.format(Date(error.optLong("time")))} ${error.optString("message")}")
                        error.optJSONArray("stack")?.strings()?.take(10)?.forEach { appendLine("      $it") }
                    }
                }
            }
        }
    }

    private fun packageInfo(packageName: String): PackageInfo = context.packageManager.getPackageInfo(
        packageName,
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_DISABLED_COMPONENTS
    )

    private fun describePackage(info: PackageInfo): JSONObject {
        val application = info.applicationInfo
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        return JSONObject().apply {
            put("package", info.packageName)
            put("versionName", info.versionName)
            put("versionCode", info.longVersionCode)
            put("targetSdk", application?.targetSdkVersion)
            put("minSdk", application?.minSdkVersion)
            put("enabled", application?.enabled)
            put("system", application?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 })
            put("updatedSystemApp", application?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 })
            put("firstInstall", info.firstInstallTime)
            put("lastUpdate", info.lastUpdateTime)
            put("sourceDir", application?.sourceDir)
            put("splits", JSONArray(application?.splitSourceDirs?.toList().orEmpty()))
            put("apkSha256", application?.sourceDir?.let { runCatching { sha256(File(it).inputStream()) }.getOrNull() })
            put("signerSha256", signer?.let { sha256(it.inputStream()) })
        }
    }

    private fun loadPackageVisibility() {
        val applications = runCatching {
            context.packageManager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
        applications.forEach { application ->
            val system = application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (system || application.isXposedModule()) {
                publicPackages.add(application.packageName)
            } else {
                privatePackages.add(application.packageName)
            }
        }
        val kept = KEPT_PACKAGES + BuildConfig.APPLICATION_ID + listOf(ICON_PACKS, THEMED_ICON_PACKS).flatMap { key ->
            PACKAGE_LIKE.findAll(RPrefs.all[key]?.toString().orEmpty()).map { it.value }.toList()
        }
        publicPackages.addAll(kept)
        privatePackages.removeAll(kept.toSet())
    }

    private fun ApplicationInfo.isXposedModule(): Boolean {
        if (metaData?.containsKey(XPOSED_META) == true) return true
        return runCatching { ZipFile(sourceDir).use { it.getEntry(XPOSED_INIT) != null } }.getOrDefault(false)
    }

    private fun anonymize(text: String, strict: Boolean = false): String {
        if (strict) return anonymizeNames(text, true)
        return text.lineSequence().joinToString("\n") { line ->
            if (CODE_LINE.containsMatchIn(line)) line else anonymizeNames(line, false)
        }
    }

    private fun anonymizeNames(text: String, strict: Boolean): String = PACKAGE_LIKE.replace(text) { match ->
        val name = match.value
        val segments = name.split('.')
        for (count in segments.size downTo 2) {
            val full = segments.take(count).joinToString(".")
            val base = full.length - segments[count - 1].length
            val cuts = segments[count - 1].indices.filter { segments[count - 1][it] == '_' }.map { full.substring(0, base + it) }
            (listOf(full) + cuts.reversed()).forEach { prefix ->
                if (prefix in publicPackages) return@replace name
                if (prefix in privatePackages) return@replace token(prefix) + name.substring(prefix.length)
            }
        }
        if (strict && PUBLIC_PREFIXES.none { name.startsWith(it) }) token(name) else name
    }

    private fun token(packageName: String): String = tokens.getOrPut(packageName) {
        "app_" + sha256((salt + packageName).byteInputStream()).take(TOKEN_LENGTH)
    }

    private fun secureInt(key: String): Int? = runCatching { Settings.Secure.getInt(context.contentResolver, key) }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun writeText(name: String, text: String) {
        val file = File(workDir, name)
        file.parentFile?.mkdirs()
        file.writeText(if (name.startsWith("user/")) text else anonymize(text))
        entries[name] = file
    }

    private fun runCommand(command: String, root: Boolean): String {
        if (root) return Shell.cmd(command).exec().out.joinToString("\n")
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        }.getOrElse { "failed: $it" }
    }

    private fun rootToFile(name: String, command: String, transform: (String) -> String = { it }) {
        val raw = File(workDir, "raw/${name.replace('/', '_')}")
        rootToFile(raw, command)
        if (!raw.exists()) return
        val text = transform(raw.readText())
        raw.delete()
        if (text.isNotBlank()) writeText(name, sanitize(text))
    }

    private fun rootToFile(file: File, command: String) {
        file.parentFile?.mkdirs()
        Shell.cmd("(${command}) > '${file.absolutePath}' 2>&1; ${ownCommand(file)}").exec()
    }

    private fun sanitize(text: String, keyValues: Boolean = true): String =
        SANITIZERS.filter { keyValues || it.first != KEY_VALUE_SANITIZER }.fold(text) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }

    private fun ownCommand(file: File): String {
        val uid = Process.myUid()
        return "[ -f '${file.absolutePath}' ] && chown $uid:$uid '${file.absolutePath}' && chmod 600 '${file.absolutePath}' && " +
                "chcon \"\$(stat -c %C '${workDir.absolutePath}')\" '${file.absolutePath}'"
    }

    private fun redactProps(props: String): String = props.lines().joinToString("\n") { line ->
        if (SENSITIVE_PROP.containsMatchIn(line.substringBefore("]:"))) line.substringBefore("]:") + "]: [redacted]" else line
    }

    private fun decode(encoded: String): JSONObject {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        return JSONObject(GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() })
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

    companion object {
        const val OUTPUT_DIR = "debug_reports"
        private const val WORK_DIR = "debug_report_work"
        private const val RUNTIME_TIMEOUT_MS = 12000L
        private const val SHARED_OWNER = "Shared"
        private const val TOKEN_LENGTH = 6
        private const val XPOSED_META = "xposedmodule"
        private const val XPOSED_INIT = "META-INF/xposed/java_init.list"
        private val CODE_LINE = Regex("^\\s*(at |Caused by:|Suppressed:)|\\b[\\w$.]+(Exception|Error)\\b")
        private val PACKAGE_LIKE = Regex("(?<![\\w.])[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")
        private val KEPT_PACKAGES = listOf(
            PIXEL_LAUNCHER_PACKAGE, LAUNCHER3_PACKAGE, "org.lsposed.manager", "com.topjohnwu.magisk",
            "io.github.huskydg.magisk", "me.weishu.kernelsu", "me.bmax.apatch"
        )
        private val PUBLIC_PREFIXES = listOf("android.", "com.android.", "com.google.android.", "java.", "javax.", "kotlin.", "androidx.", "dalvik.")
        private const val MB = 1024L * 1024L
        private const val KB = 1024L
        private const val KEPT_REPORTS = 3
        private const val MAX_LOADER_EVENTS = 40
        private const val MAX_DROPBOX_ENTRIES = 10
        private const val MAX_ENTRY_LINES = 150
        private const val MAX_CRASH_FILES = 2
        private const val MAX_TOMBSTONE_LINES = 200
        private const val MAX_LSPOSED_LINES = 300
        private const val MAX_LSPOSED_PROBLEMS = 1000
        private const val RELATED_TAGS = "PLEnhanced|pixellauncherenhanced|LSPosed|LSPlant|Xposed|FATAL EXCEPTION|AndroidRuntime|ANR in"
        private val RELATED_LOG = Regex("(?i)$RELATED_TAGS")
        private val DUMP_HEADER = Regex("^ {0,4}([A-Za-z][A-Za-z0-9 _$.]*?)(?: state| history of latest events)?:\\s*$")
        private val DUMP_EVENT = Regex("^\\d{1,2}/\\d{1,2}/\\d{2} ")
        private val DUMP_SECTIONS = listOf(
            "Local Activity", "InsetsController", "Misc", "DragLayer", "StateManager", "DeviceProfile",
            "Data Model", "SystemUiController", "QuickstepLauncher", "OneSearch", "LauncherSmartspace",
            "DeviceSearchSettings", "OneGridMigrationEduDialog", "ContextTracker"
        )
        private val PACKAGE_FIELDS = Regex("(?i)(Package \\[|versionCode|versionName|codePath|resourcePath|primaryCpuAbi|pkgFlags|privatePkgFlags|flags=|timeStamp|lastUpdateTime|firstInstallTime|installerPackageName|splits|apkSigningVersion|signatures|minSdk|targetSdk|User \\d+:|enabled=|stopped=|sharedUser|dataDir)")
        private val MODULE_FRAMES = Regex("drdisagree|LSPHooker|de\\.robv\\.android\\.xposed|org\\.lsposed")
        private val PROP_KEEP = Regex("(?i)\\[(ro\\.(build|product|system|system_ext|vendor|odm)\\.|ro\\.(lineage|crdroid|infinity|evolution|axion|derp|pixelos|rising|matrixx|custom|aosp|potato|arrow|statix|afterlife|yaap|voltage|alpha|sakura|cherish|havoc|superior|pixys)|ro\\.modversion|ro\\.(sf|surface_flinger)|ro\\.config\\.|ro\\.treble|ro\\.debuggable|ro\\.secure|ro\\.boot\\.(verifiedbootstate|flash\\.locked|vbmeta\\.device_state)|persist\\.sys\\.(launcher|theme|font|locale|timezone|disable_rescue)|launcher|quickstep|recents|zygisk|lsposed|magisk|ksu|kernelsu|apatch|persist\\.lsp|dalvik\\.vm\\.isa|ro\\.zygote)")
        private const val DROPBOX_SEPARATOR = "========================================"
        private const val GLOBAL_SETTINGS = "animat|transition|freeform|desktop|force_resizable|development|window_|navigation|blur"
        private const val SECURE_SETTINGS = "navigation|gesture|launcher|icon|theme|overlay|taskbar|sysui|font|dark|density|swipe|assist_gesture|one_handed"
        private const val SYSTEM_SETTINGS = "font|display|theme|screen_off|haptic|accelerometer"
        private val LAYOUT_COLUMNS = setOf(
            "_id", "container", "screen", "cellX", "cellY", "spanX", "spanY", "itemType", "rank",
            "appWidgetId", "appWidgetProvider", "restored", "profileId", "options", "appWidgetSource"
        )
        private val EXCLUDED_SETTINGS = setOf(
            DEBUG_REPORT_REQUEST, DEBUG_REPORT_RESPONSE, SHORTCUTS_REQUEST, SHORTCUTS_RESPONSE,
            PINNED_SHORTCUTS_REQUEST, BACKUP_HOME_LAYOUT, BACKUP_DRAWER_LAYOUT, BACKUP_SETTINGS
        )
        private val EXCLUDED_SETTING_PREFIXES = listOf("xposed_customicon_")
        private val PRIVATE_SETTINGS = setOf(SHORTCUT_LABELS, LABEL_OVERRIDES, PINNED_SHORTCUTS, DRAWER_TABS, DRAWER_FOLDERS)
        private val SENSITIVE_PROP = Regex("serial|imei|meid|iccid|mac|bt\\.addr|wifi\\.addr|ssid|android_id|bootid|cpuid|uuid", RegexOption.IGNORE_CASE)
        private val KEY_VALUE_SANITIZER = Regex("(?i)\\b(dat|data|title|label|text|name|account|ssid|bssid)=([^,;}\\]\\n]+)")
        private val SANITIZERS = listOf(
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "<email>",
            Regex("(?i)\\b(tel|sms|smsto|mailto|geo|content|file|https?):[^\\s,;}\\]\"']+") to "<uri>",
            KEY_VALUE_SANITIZER to "$1=<redacted>",
            Regex("\\+\\d[\\d -]{7,}\\d") to "<number>",
            Regex("\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b") to "<mac>",
            Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b") to "<ip>"
        )
    }
}
