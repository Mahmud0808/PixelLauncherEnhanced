package com.drdisagree.pixellauncherenhanced.utils

import android.app.WallpaperInfo
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Process
import android.util.Xml
import android.view.Display
import android.view.SurfaceControlViewHost
import androidx.annotation.RequiresApi
import androidx.core.os.BundleCompat
import com.drdisagree.pixellauncherenhanced.data.common.Constants.LAUNCHER3_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PIXEL_LAUNCHER_PACKAGE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.XPOSED_HOOK_CHECK
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.HomePreview
import com.drdisagree.pixellauncherenhanced.utils.AppUtils.isLauncher3
import com.drdisagree.pixellauncherenhanced.utils.AppUtils.isPixelLauncher
import com.topjohnwu.superuser.Shell
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object HomePreviewLoader {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loading = AtomicBoolean(false)

    @Volatile
    var cached: HomePreview? = null
        private set

    private var wallpaperKey: String? = null
    private var wallpaper: Drawable? = null

    fun load(context: Context, width: Int, height: Int, onResult: (HomePreview?) -> Unit) {
        if (width <= 0 || height <= 0 || !loading.compareAndSet(false, true)) return
        val appContext = context.applicationContext

        executor.execute {
            val preview = runCatching { capture(appContext, width, height) }.getOrNull()
            cached = preview
            loading.set(false)
            mainHandler.post { onResult(preview) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun requestSurface(
        context: Context,
        authority: String,
        hostToken: IBinder,
        width: Int,
        height: Int,
        onResult: (SurfaceControlViewHost.SurfacePackage?, Message?) -> Unit
    ) {
        val appContext = context.applicationContext

        executor.execute {
            val result = runCatching {
                val extras = previewExtras(width, height).apply {
                    putBinder(KEY_HOST_TOKEN, hostToken)
                }
                appContext.contentResolver.call(previewUri(authority), METHOD_PREVIEW, null, extras)
            }.getOrNull()

            val surface = result?.let {
                BundleCompat.getParcelable(it, KEY_SURFACE_PACKAGE, SurfaceControlViewHost.SurfacePackage::class.java)
            }
            val callback = result?.let { BundleCompat.getParcelable(it, KEY_CALLBACK, Message::class.java) }
            mainHandler.post { onResult(surface, callback) }
        }
    }

    fun releaseSurface(callback: Message?) {
        val messenger = callback?.replyTo ?: return
        runCatching { messenger.send(Message.obtain().apply { what = MESSAGE_DESTROY }) }
    }

    private fun capture(context: Context, width: Int, height: Int): HomePreview? {
        if (!RPrefs.getBoolean(XPOSED_HOOK_CHECK, false)) return null
        val authority = launcherAuthority() ?: return null

        RootShell.init()
        if (!RootShell.isRootShell()) return null

        val background = loadWallpaper(context, width, height)
        val result = runCatching {
            context.contentResolver.call(previewUri(authority), METHOD_PREVIEW_BITMAP, null, previewExtras(width, height).apply {
                putLong(KEY_DELAY, RENDER_DELAY_MS)
            })
        }.getOrNull()
        val screen = result?.let { BundleCompat.getParcelable(it, KEY_IMAGE, Bitmap::class.java) }

        return when {
            screen != null -> HomePreview(screen, background, authority)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HomePreview(null, background, authority)
            else -> null
        }
    }

    private fun launcherAuthority(): String? = when {
        isPixelLauncher -> PIXEL_LAUNCHER_PACKAGE
        isLauncher3 -> LAUNCHER3_PACKAGE
        else -> null
    }?.let { "$it.grid_control" }

    private fun previewUri(authority: String) = Uri.parse("content://$authority")

    private fun previewExtras(width: Int, height: Int) = Bundle().apply {
        putInt(KEY_WIDTH, width)
        putInt(KEY_HEIGHT, height)
        putInt(KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY)
    }

    private fun loadWallpaper(context: Context, width: Int, height: Int): Drawable? {
        val manager = WallpaperManager.getInstance(context)
        val info = manager.wallpaperInfo
        val key = "${info?.component}:${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}:${width}x$height"
        if (key == wallpaperKey) return wallpaper

        wallpaper = if (info != null) {
            readLiveWallpaper(context, info, width, height)
                ?: runCatching { info.loadThumbnail(context.packageManager) }.getOrNull()
        } else {
            readRootImage(context, width, height) { "cat '$systemDir/wallpaper' > '$it'" }
        }
        wallpaperKey = key
        return wallpaper
    }

    private val userId get() = Process.myUid() / PER_USER_RANGE

    private val systemDir get() = "/data/system/users/$userId"

    private fun readLiveWallpaper(context: Context, info: WallpaperInfo, width: Int, height: Int): Drawable? {
        if (info.packageName == EFFECTS_PACKAGE) {
            val dir = "/data/user_de/$userId/$EFFECTS_PACKAGE"
            val prefs = "$dir/shared_prefs/$EFFECTS_PREFS"
            readRootImage(context, width, height) { out ->
                "id=\$(sed -n 's/.*name=\"$EFFECTS_ACTIVE_ASSET\" value=\"\\([0-9]*\\)\".*/\\1/p' '$prefs'); " +
                        "for f in $EFFECTS_IMAGES; do " +
                        "[ -n \"\$id\" ] && [ -s \"$dir/files/\$id/\$f\" ] && cat \"$dir/files/\$id/\$f\" > '$out' && break; " +
                        "done"
            }?.let { return it }
        }

        val thumbnail = descriptionThumbnail() ?: return null
        return readRootImage(context, width, height) { out ->
            if (thumbnail.scheme == "file") {
                "cat '${thumbnail.path.orEmpty().shellQuoted()}' > '$out'"
            } else {
                "content read --user $userId --uri '${thumbnail.toString().shellQuoted()}' > '$out'"
            }
        }
    }

    private fun descriptionThumbnail(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        val file = "$systemDir/wallpaper_info.xml"
        val xml = Shell.cmd("abx2xml '$file' - 2>/dev/null || cat '$file'").exec().out.joinToString("\n")
        if (xml.isBlank()) return null

        return runCatching {
            val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
            var inSystem = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == TAG_SYSTEM_WALLPAPER) inSystem = false
                if (parser.eventType != XmlPullParser.START_TAG) continue
                if (parser.name == TAG_SYSTEM_WALLPAPER) inSystem = true
                if (inSystem && parser.name == TAG_DESCRIPTION) {
                    return@runCatching parser.getAttributeValue(null, ATTR_THUMBNAIL)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Uri.parse(it) }
                        ?.takeIf { it.scheme == "content" || it.scheme == "file" }
                }
            }
            null
        }.getOrNull()
    }

    private fun String.shellQuoted() = replace("'", "'\\''")

    private fun readRootImage(context: Context, width: Int, height: Int, command: (String) -> String): Drawable? {
        val file = File(context.cacheDir, WALLPAPER_FILE)

        return try {
            file.delete()
            file.createNewFile()
            Shell.cmd(command(file.path)).exec()
            if (file.length() == 0L) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2

            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { BitmapDrawable(context.resources, it) }
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    private const val METHOD_PREVIEW = "get_preview"
    private const val METHOD_PREVIEW_BITMAP = "get_preview_bitmap"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_DISPLAY_ID = "display_id"
    private const val KEY_DELAY = "bitmap_delay_ms"
    private const val KEY_HOST_TOKEN = "host_token"
    private const val KEY_IMAGE = "image"
    private const val KEY_SURFACE_PACKAGE = "surface_package"
    private const val KEY_CALLBACK = "callback"
    private const val MESSAGE_DESTROY = 0
    private const val RENDER_DELAY_MS = 300L
    private const val WALLPAPER_FILE = "home_preview_wallpaper"
    private const val PER_USER_RANGE = 100000
    private const val EFFECTS_PACKAGE = "com.google.android.wallpaper.effects"
    private const val EFFECTS_PREFS = "cinematic_preferences.xml"
    private const val EFFECTS_ACTIVE_ASSET = "active_persistent_asset_id"
    private const val EFFECTS_IMAGES = "original.jpg thumbnail.jpg"
    private const val TAG_SYSTEM_WALLPAPER = "wp"
    private const val TAG_DESCRIPTION = "description"
    private const val ATTR_THUMBNAIL = "thumbnail"
}
