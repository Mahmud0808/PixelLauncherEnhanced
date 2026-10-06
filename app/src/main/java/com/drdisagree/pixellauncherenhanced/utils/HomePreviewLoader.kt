package com.drdisagree.pixellauncherenhanced.utils

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
import java.io.File
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
                val extras = previewExtras(appContext, width, height).apply {
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
        if (Shell.getShell().isRoot.not()) return null

        val background = loadWallpaper(context, width, height)
        val result = runCatching {
            context.contentResolver.call(previewUri(authority), METHOD_PREVIEW_BITMAP, null, previewExtras(context, width, height).apply {
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

    private fun previewExtras(context: Context, width: Int, height: Int) = Bundle().apply {
        putInt(KEY_WIDTH, width)
        putInt(KEY_HEIGHT, height)
        putInt(KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                ?.let { putParcelable(KEY_WALLPAPER_COLORS, it) }
        }
    }

    private fun loadWallpaper(context: Context, width: Int, height: Int): Drawable? {
        val manager = WallpaperManager.getInstance(context)
        val info = manager.wallpaperInfo
        val key = "${info?.component}:${manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)}:${width}x$height"
        if (key == wallpaperKey) return wallpaper

        wallpaper = if (info != null) {
            runCatching { info.loadThumbnail(context.packageManager) }.getOrNull()
        } else {
            readStaticWallpaper(context, width, height)
        }
        wallpaperKey = key
        return wallpaper
    }

    private fun readStaticWallpaper(context: Context, width: Int, height: Int): Drawable? {
        val file = File(context.cacheDir, WALLPAPER_FILE)

        return try {
            file.delete()
            file.createNewFile()
            Shell.cmd("cat '/data/system/users/${Process.myUid() / PER_USER_RANGE}/wallpaper' > '${file.path}'").exec()
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
    private const val KEY_WALLPAPER_COLORS = "wallpaper_colors"
    private const val KEY_HOST_TOKEN = "host_token"
    private const val KEY_IMAGE = "image"
    private const val KEY_SURFACE_PACKAGE = "surface_package"
    private const val KEY_CALLBACK = "callback"
    private const val MESSAGE_DESTROY = 0
    private const val RENDER_DELAY_MS = 300L
    private const val WALLPAPER_FILE = "home_preview_wallpaper"
    private const val PER_USER_RANGE = 100000
}
