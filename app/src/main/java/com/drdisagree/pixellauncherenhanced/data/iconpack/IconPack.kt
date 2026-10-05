package com.drdisagree.pixellauncherenhanced.data.iconpack

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.roundToInt

class IconPack(context: Context, val packageName: String) {

    private val resources: Resources = context.packageManager.getResourcesForApplication(packageName)
    private val packContext: Context = context.createPackageContext(packageName, 0)

    private val componentIcons = HashMap<String, String>()
    private val resourceIds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val packageIcons = HashMap<String, String>()
    private val calendarPrefixes = HashMap<String, String>()
    private val backImages = ArrayList<String>()
    private var maskImage: String? = null
    private var uponImage: String? = null
    private var scale = 1f

    val hasMask: Boolean
        get() = backImages.isNotEmpty() || maskImage != null || uponImage != null

    val coveredComponents: Set<String>
        get() = componentIcons.keys

    init {
        parseAppFilter()
    }

    fun drawableNameFor(component: ComponentName): String? {
        val key = component.flattenToString()
        calendarPrefixes[key]?.let { prefix ->
            val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
            return prefix + day
        }
        return listOfNotNull(componentIcons[key], packageIcons[component.packageName])
            .firstOrNull { hasDrawable(it) }
    }

    fun hasDrawable(name: String) = resourceId(name) != 0

    @SuppressLint("DiscouragedApi")
    private fun resourceId(name: String): Int {
        drawableIds[name]?.let { return it }
        if (drawableIds.isNotEmpty()) return 0

        return resourceIds.getOrPut(name) {
            resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
                ?: resources.getIdentifier(name, "mipmap", packageName)
        }
    }

    @Suppress("DiscouragedApi")
    private val drawableIds: Map<String, Int> by lazy {
        val ids = HashMap<String, Int>()
        val probe = componentIcons.values.take(PROBE_LIMIT) + "ic_launcher"

        listOf("drawable", "mipmap").forEach { type ->
            val sample = probe.firstNotNullOfOrNull { name ->
                resources.getIdentifier(name, type, packageName).takeIf { it != 0 }
            } ?: return@forEach

            val base = sample and 0xFFFF0000.toInt()
            var entry = 0
            while (entry <= 0xFFFF) {
                val name = runCatching { resources.getResourceEntryName(base or entry) }.getOrNull() ?: break
                ids.putIfAbsent(name, base or entry)
                entry++
            }
        }

        ids
    }

    fun covers(component: ComponentName) = drawableNameFor(component) != null

    @SuppressLint("DiscouragedApi")
    fun loadDrawable(name: String, density: Int = 0): Drawable? {
        val id = resourceId(name).takeIf { it != 0 } ?: return null

        return runCatching {
            if (density > 0) resources.getDrawableForDensity(id, density, null) else resources.getDrawable(id, null)
        }.getOrNull()
    }

    fun iconFor(component: ComponentName, density: Int = 0): Drawable? {
        val name = drawableNameFor(component) ?: return null
        return loadDrawable(name, density)
    }

    fun maskIcon(original: Drawable, size: Int, seed: Int): Drawable? {
        if (!hasMask) return null

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        backImages.takeIf { it.isNotEmpty() }
            ?.let { loadDrawable(it[Math.floorMod(seed, it.size)]) }
            ?.apply { setBounds(0, 0, size, size) }
            ?.draw(canvas)

        val iconLayer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val iconCanvas = Canvas(iconLayer)
        val scaled = (size * scale).roundToInt()
        val offset = (size - scaled) / 2
        original.setBounds(offset, offset, offset + scaled, offset + scaled)
        original.draw(iconCanvas)

        maskImage?.let { loadDrawable(it) }?.let { mask ->
            val maskBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            mask.setBounds(0, 0, size, size)
            mask.draw(Canvas(maskBitmap))
            iconCanvas.drawBitmap(maskBitmap, 0f, 0f, Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            })
        }

        canvas.drawBitmap(iconLayer, 0f, 0f, null)

        uponImage?.let { loadDrawable(it) }?.apply { setBounds(0, 0, size, size) }?.draw(canvas)

        return BitmapDrawable(resources, bitmap)
    }

    fun allDrawableNames(): List<String> = categories().flatMap { it.second }.distinct()

    @SuppressLint("DiscouragedApi")
    fun categories(): List<Pair<String?, List<String>>> {
        val grouped = LinkedHashMap<String?, LinkedHashSet<String>>()
        val id = resources.getIdentifier("drawable", "xml", packageName)

        val parser = when {
            id != 0 -> resources.getXml(id)
            else -> openAsset("drawable.xml")?.let { newParser(it) }
        }

        parser?.let {
            runCatching {
                var category: String? = null
                var event = it.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (it.name) {
                            "category" -> category = it.getAttributeValue(null, "title")?.takeIf { title -> title.isNotBlank() }
                            "item" -> it.getAttributeValue(null, "drawable")
                                ?.takeIf { name -> hasDrawable(name) }
                                ?.let { name -> grouped.getOrPut(category) { LinkedHashSet() }.add(name) }
                        }
                    }
                    event = it.next()
                }
            }
        }

        val listed = grouped.values.flatten().toHashSet()
        val remaining = componentIcons.values.distinct().filter { it !in listed && hasDrawable(it) }
        if (remaining.isNotEmpty()) grouped.getOrPut(if (grouped.keys.any { it != null }) OTHER_CATEGORY else null) { LinkedHashSet() }.addAll(remaining)

        return grouped.filterValues { it.isNotEmpty() }.map { (title, names) -> title to names.toList() }
    }

    @SuppressLint("DiscouragedApi")
    private fun parseAppFilter() {
        val id = resources.getIdentifier("appfilter", "xml", packageName)
        val parser = when {
            id != 0 -> resources.getXml(id)
            else -> openAsset("appfilter.xml")?.let { newParser(it) }
        } ?: return

        runCatching {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> {
                            val component = parser.getAttributeValue(null, "component")
                            val drawable = parser.getAttributeValue(null, "drawable")
                            if (component != null && !drawable.isNullOrEmpty()) addMapping(component, drawable)
                        }

                        "calendar" -> {
                            val component = parser.getAttributeValue(null, "component")?.let(::parseComponent)
                            val prefix = parser.getAttributeValue(null, "prefix")
                            if (component != null && prefix != null) calendarPrefixes[component.flattenToString()] = prefix
                        }

                        "iconback" -> for (i in 0 until parser.attributeCount) {
                            parser.getAttributeValue(i)?.takeIf { it.isNotEmpty() }?.let(backImages::add)
                        }

                        "iconmask" -> maskImage = parser.getAttributeValue(null, "img1")
                        "iconupon" -> uponImage = parser.getAttributeValue(null, "img1")
                        "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                    }
                }
                event = parser.next()
            }
        }
    }

    private fun addMapping(rawComponent: String, drawable: String) {
        val component = parseComponent(rawComponent) ?: run {
            val packageOnly = rawComponent.removePrefix("ComponentInfo{").removeSuffix("}")
            if (packageOnly.isNotEmpty() && !packageOnly.contains('/')) packageIcons.putIfAbsent(packageOnly, drawable)
            return
        }

        componentIcons.putIfAbsent(component.flattenToString(), drawable)
        packageIcons.putIfAbsent(component.packageName, drawable)
    }

    private fun parseComponent(raw: String): ComponentName? {
        val value = raw.removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.length - 1) return null

        val pkg = value.substring(0, slash)
        val cls = value.substring(slash + 1).let { if (it.startsWith(".")) pkg + it else it }
        return ComponentName(pkg, cls)
    }

    companion object {
        private const val PROBE_LIMIT = 50
        const val OTHER_CATEGORY = "\u0000other"
    }

    private fun openAsset(name: String): InputStream? = runCatching { packContext.assets.open(name) }.getOrNull()

    private fun newParser(input: InputStream): XmlPullParser {
        return XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(input, "UTF-8")
        }
    }
}
