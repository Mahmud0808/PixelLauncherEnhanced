package com.drdisagree.pixellauncherenhanced.xposed.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DEBUG_REPORT_RESPONSE
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

object DebugRecorder {

    private const val MAX_ERRORS = 200
    private const val MAX_STACK_FRAMES = 24
    private const val MAX_ITEMS = 12
    private val IGNORED_FIELDS = setOf("Companion", "INSTANCE", "\$stable")
    private val IDENTIFIER = Regex("[A-Za-z0-9_.$:/#-]{0,160}")
    private const val MOD_PACKAGE = "com.drdisagree.pixellauncherenhanced.xposed.mods."
    private const val TOOLKIT_PACKAGE = "com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit."

    class ClassLookup(val candidates: List<String>, val resolved: String?, val owner: String?, val optional: Boolean)

    class HookRecord(
        val owner: String?,
        val className: String?,
        val requested: List<String>,
        val kind: String,
        val pattern: Boolean,
        val resolved: List<String>,
        val suppressed: Boolean,
        val capability: String?
    ) {
        @JvmField
        var fired = 0L

        @JvmField
        var firstFiredAt = 0L
    }

    class MemberMiss(val kind: String, val className: String, val names: List<String>, val owner: String?) {
        val count = AtomicLong()
    }

    class FieldFallback(val className: String, val candidates: List<String>, val resolved: String, val owner: String?)

    class ModResult(val name: String, val loadMs: Long, val prefsError: String?, val loadError: String?)

    class ErrorRecord(val time: Long, val owner: String?, val message: String, val stack: List<String>)

    private val currentOwner = ThreadLocal<String?>()
    private val classLookups = ConcurrentHashMap<String, ClassLookup>()
    private val hooks = ConcurrentHashMap<String, HookRecord>()
    private val memberMisses = ConcurrentHashMap<String, MemberMiss>()
    private val fieldFallbacks = ConcurrentHashMap<String, FieldFallback>()
    private val modResults = ArrayList<ModResult>()
    private val errors = ArrayDeque<ErrorRecord>()
    private val probes = ConcurrentHashMap<String, () -> Any?>()
    private val healthChecks = ArrayList<HealthCheck>()

    class HealthCheck(val owner: String?, val name: String, val check: () -> Boolean?)

    private val captures = ArrayList<CaptureNeed>()

    class CaptureNeed(val owner: String?, val field: String, val needed: () -> Boolean)
    private var modInstances: () -> List<Any> = { emptyList() }
    private val executor = Executors.newSingleThreadExecutor()
    private val processStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())

    @SuppressLint("StaticFieldLeak")
    private var context: Context? = null
    private var packageName: String? = null

    fun attach(context: Context, packageName: String) {
        this.context = context.applicationContext ?: context
        this.packageName = packageName
    }

    fun <T> withOwner(owner: String, block: () -> T): T {
        val previous = currentOwner.get()
        currentOwner.set(owner)
        return try {
            block()
        } finally {
            currentOwner.set(previous)
        }
    }

    fun registerProbe(name: String, probe: () -> Any?) {
        probes[name] = probe
    }

    fun registerHealth(name: String, check: () -> Boolean?) {
        synchronized(healthChecks) { healthChecks.add(HealthCheck(owner(), name, check)) }
    }

    fun registerCapture(field: String, needed: () -> Boolean) {
        synchronized(captures) { captures.add(CaptureNeed(owner(), field, needed)) }
    }

    fun registerModInstances(provider: () -> List<Any>) {
        modInstances = provider
    }

    fun recordModResult(name: String, loadMs: Long, prefsError: Throwable?, loadError: Throwable?) {
        synchronized(modResults) {
            modResults.add(ModResult(name, loadMs, prefsError?.describe(), loadError?.describe()))
        }
    }

    fun recordClassLookup(candidates: Array<out String>, resolved: String?, optional: Boolean) {
        val key = candidates.joinToString("|")
        if (classLookups.containsKey(key)) return
        classLookups.putIfAbsent(key, ClassLookup(candidates.toList(), resolved, owner(), optional))
    }

    fun recordHook(
        clazz: Class<*>?,
        requested: Array<out String>?,
        kind: String,
        pattern: Boolean,
        resolved: List<Member>,
        suppressed: Boolean,
        capability: String? = null
    ): HookRecord {
        val names = requested?.toList() ?: listOf("<init>")
        val key = "${clazz?.name}#${names.joinToString(",")}#$kind#${resolved.size}#$capability"
        return hooks.getOrPut(key) {
            HookRecord(
                owner(),
                clazz?.name,
                names,
                kind,
                pattern,
                resolved.map { it.signature() },
                suppressed,
                capability
            )
        }
    }

    fun recordDirectHook(member: Member, kind: String): HookRecord {
        val key = "${member.declaringClass.name}#${member.signature()}#$kind"
        return hooks.getOrPut(key) {
            HookRecord(owner(), member.declaringClass.name, listOf(member.name), kind, false, listOf(member.signature()), false, null)
        }
    }

    fun fired(record: HookRecord) {
        if (record.fired++ == 0L) record.firstFiredAt = System.currentTimeMillis()
    }

    fun recordMemberMiss(kind: String, target: Any?, vararg names: String): AtomicLong {
        val className = when (target) {
            null -> "<null>"
            is Class<*> -> target.name
            else -> target.javaClass.name
        }
        val key = "$kind|$className|${names.joinToString(",")}"
        val record = memberMisses[key] ?: memberMisses.getOrPut(key) { MemberMiss(kind, className, names.toList(), owner()) }
        record.count.incrementAndGet()
        return record.count
    }

    fun recordFieldFallback(target: Any?, candidates: Array<out String>, resolved: String) {
        if (candidates.firstOrNull() == resolved) return
        val className = (target as? Class<*>)?.name ?: target?.javaClass?.name ?: return
        val key = "$className|${candidates.joinToString(",")}"
        if (fieldFallbacks.containsKey(key)) return
        fieldFallbacks.putIfAbsent(key, FieldFallback(className, candidates.toList(), resolved, owner()))
    }

    fun recordError(owner: String?, message: String?, throwable: Throwable?) {
        val record = ErrorRecord(
            System.currentTimeMillis(),
            owner ?: owner(),
            listOfNotNull(message, throwable?.toString()).joinToString(" | "),
            throwable?.let { stackOf(it) }.orEmpty()
        )
        synchronized(errors) {
            if (errors.size >= MAX_ERRORS) errors.removeFirst()
            errors.addLast(record)
        }
    }

    fun respond(requestId: String) {
        if (packageName == null) return
        executor.execute {
            val payload = runCatching { snapshot(requestId).toString() }
                .getOrElse { JSONObject().put("requestId", requestId).put("error", it.describe()).toString() }
            val encoded = runCatching { encode(payload) }.getOrNull() ?: return@execute
            runCatching { Xprefs.edit().putString(DEBUG_REPORT_RESPONSE, encoded).apply() }
        }
    }

    private fun snapshot(requestId: String): JSONObject {
        val context = context
        val root = JSONObject()
        root.put("requestId", requestId)
        root.put("generatedAt", System.currentTimeMillis())
        root.put("package", packageName)
        root.put("process", JSONObject().apply {
            put("pid", Process.myPid())
            put("uid", Process.myUid())
            put("startedAt", processStart)
            put("processName", runCatching { android.app.Application.getProcessName() }.getOrNull())
        })
        root.put("module", JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("buildType", BuildConfig.BUILD_TYPE)
        })
        root.put("xposed", JSONObject().apply {
            put("apiVersion", runCatching { XposedBridge.getXposedVersion() }.getOrNull())
            put("bridgeClassLoader", runCatching { XposedBridge::class.java.classLoader?.toString() }.getOrNull())
            put("bridgeSource", runCatching {
                XposedBridge::class.java.protectionDomain?.codeSource?.location?.toString()
            }.getOrNull())
        })
        root.put("launcher", JSONObject().apply {
            val info = runCatching { context?.packageManager?.getPackageInfo(context.packageName, 0) }.getOrNull()
            put("versionName", info?.versionName)
            put("versionCode", info?.longVersionCode)
            put("sourceDir", info?.applicationInfo?.sourceDir)
        })
        root.put("mods", JSONArray().apply {
            synchronized(modResults) {
                modResults.forEach {
                    put(JSONObject().put("name", it.name).put("loadMs", it.loadMs)
                        .put("prefsError", it.prefsError).put("loadError", it.loadError))
                }
            }
        })
        root.put("classLookups", JSONArray().apply {
            classLookups.values.sortedWith(compareBy({ it.resolved != null }, { it.owner }, { it.candidates.first() })).forEach {
                put(JSONObject().put("owner", it.owner).put("candidates", JSONArray(it.candidates))
                    .put("resolved", it.resolved).put("optional", it.optional)
                    .put("fallback", it.resolved != null && it.resolved != it.candidates.first()))
            }
        })
        root.put("hooks", JSONArray().apply {
            hooks.values.sortedWith(compareBy({ it.resolved.isNotEmpty() }, { it.owner }, { it.className })).forEach {
                put(JSONObject().put("owner", it.owner).put("class", it.className)
                    .put("requested", JSONArray(it.requested)).put("kind", it.kind).put("pattern", it.pattern)
                    .put("resolved", JSONArray(it.resolved)).put("suppressed", it.suppressed).put("capability", it.capability)
                    .put("fired", it.fired).put("firstFiredAt", it.firstFiredAt))
            }
        })
        root.put("memberMisses", JSONArray().apply {
            memberMisses.values.sortedByDescending { it.count.get() }.forEach {
                put(JSONObject().put("kind", it.kind).put("class", it.className)
                    .put("names", JSONArray(it.names)).put("owner", it.owner).put("count", it.count.get()))
            }
        })
        root.put("fieldFallbacks", JSONArray().apply {
            fieldFallbacks.values.forEach {
                put(JSONObject().put("class", it.className).put("candidates", JSONArray(it.candidates))
                    .put("resolved", it.resolved).put("owner", it.owner))
            }
        })
        root.put("errors", JSONArray().apply {
            synchronized(errors) {
                errors.forEach {
                    put(JSONObject().put("time", it.time).put("owner", it.owner)
                        .put("message", it.message).put("stack", JSONArray(it.stack)))
                }
            }
        })
        root.put("probes", JSONObject().apply {
            probes.toSortedMap().forEach { (name, probe) ->
                put(name, runCatching { probe()?.toString() }.getOrElse { "error: ${it.describe()}" })
            }
        })
        root.put("health", JSONArray().apply {
            synchronized(healthChecks) {
                healthChecks.forEach { health ->
                    val result = runCatching { health.check() }
                    put(JSONObject().put("owner", health.owner).put("name", health.name)
                        .put("result", result.getOrNull() ?: JSONObject.NULL)
                        .put("error", result.exceptionOrNull()?.describe()))
                }
            }
        })
        root.put("captures", JSONArray().apply {
            synchronized(captures) {
                captures.forEach { capture ->
                    put(JSONObject().put("owner", capture.owner).put("field", capture.field)
                        .put("needed", runCatching { capture.needed() }.getOrDefault(true)))
                }
            }
        })
        root.put("modState", JSONObject().apply {
            runCatching { modInstances() }.getOrDefault(emptyList()).forEach { mod ->
                put(mod.javaClass.simpleName, runCatching { describeFields(mod) }.getOrElse { JSONObject().put("error", it.describe()) })
            }
        })
        return root
    }

    private fun describeFields(instance: Any): JSONObject {
        val fields = JSONObject()
        var type: Class<*>? = instance.javaClass
        while (type != null && type != Any::class.java && !type.name.startsWith("android.")) {
            type.declaredFields.forEach { field ->
                val modifiers = field.modifiers
                val static = java.lang.reflect.Modifier.isStatic(modifiers)
                if (field.isSynthetic || fields.has(field.name) || field.name in IGNORED_FIELDS) return@forEach
                if (static && java.lang.reflect.Modifier.isFinal(modifiers) &&
                    (field.type.isPrimitive || field.type == String::class.java)
                ) return@forEach
                field.isAccessible = true
                val value = runCatching { field.get(if (static) null else instance) }.getOrElse { "<${it.javaClass.simpleName}>" }
                fields.put(field.name, describeValue(value))
            }
            type = type.superclass
        }
        return fields
    }

    private fun describeValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Boolean, is Number, is Char -> value
        is String -> describeText(value)
        is Enum<*> -> value.name
        is CharSequence -> describeText(value.toString())
        is Collection<*> -> "${value.javaClass.simpleName}(size=${value.size})"
        is Map<*, *> -> "${value.javaClass.simpleName}(size=${value.size})"
        is Array<*> -> "Array(size=${value.size})"
        is IntArray -> value.take(MAX_ITEMS).joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.take(MAX_ITEMS).joinToString(prefix = "[", postfix = "]")
        is java.lang.ref.Reference<*> -> "ref(${value.get().shortDescription()})"
        is android.view.View -> "${value.javaClass.name}(attached=${value.isAttachedToWindow}, shown=${value.isShown}, ${value.width}x${value.height})"
        is Class<*> -> "class ${value.name}"
        is Context -> value.javaClass.name
        else -> value.shortDescription()
    }

    private fun describeText(value: String): String =
        if (IDENTIFIER.matches(value)) value else "<text, ${value.length} chars>"

    private fun Any?.shortDescription(): String = when (this) {
        null -> "null"
        is Number, is Boolean, is Enum<*> -> toString()
        else -> javaClass.name
    }

    fun encode(payload: String): String {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(payload.toByteArray()) }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun owner(): String? {
        currentOwner.get()?.let { return it }
        return Throwable().stackTrace.firstOrNull {
            it.className.startsWith(MOD_PACKAGE) && !it.className.startsWith(TOOLKIT_PACKAGE)
        }?.className?.removePrefix(MOD_PACKAGE)?.substringBefore('$')
    }

    private fun stackOf(throwable: Throwable): List<String> {
        val lines = ArrayList<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) lines.add("Caused by: $current")
            current.stackTrace.take(MAX_STACK_FRAMES).forEach { lines.add("at $it") }
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return lines
    }

    private fun Throwable.describe(): String = "${javaClass.name}: $message"

    private fun Member.signature(): String = when (this) {
        is java.lang.reflect.Method -> "$name(${parameterTypes.joinToString { it.simpleName }}): ${returnType.simpleName}"
        is java.lang.reflect.Constructor<*> -> "<init>(${parameterTypes.joinToString { it.simpleName }})"
        else -> name
    }
}
