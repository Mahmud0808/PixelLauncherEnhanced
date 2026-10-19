package com.drdisagree.pixellauncherenhanced.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_AUTO_CHECK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_CACHE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_LAST_CHECK
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_NOTIFICATION_ASKED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_NOTIFIED
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_NOTIFY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.UPDATE_SKIPPED
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.data.model.DevBuild
import com.drdisagree.pixellauncherenhanced.data.model.ReleaseInfo
import com.drdisagree.pixellauncherenhanced.services.UpdateCheckJob
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object UpdateManager {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: ReleaseInfo?) : State
        data class Available(val release: ReleaseInfo) : State
        data class DevAvailable(val build: DevBuild) : State
        data class Downloading(val release: ReleaseInfo, val downloaded: Long, val total: Long) : State
        data class Ready(val release: ReleaseInfo, val file: File) : State
        data class Failed(val error: Error, val release: ReleaseInfo?) : State
    }

    enum class Error { CHECK, DOWNLOAD, SIGNATURE }

    private const val API_URL = "https://api.github.com/repos/Mahmud0808/PixelLauncherEnhanced/releases/latest"
    private const val CHANGELOG_URL = "https://api.github.com/repos/Mahmud0808/PixelLauncherEnhanced/releases?per_page=30"
    private const val DEV_RUNS_URL =
        "https://api.github.com/repos/Mahmud0808/PixelLauncherEnhanced/actions/workflows/build_debug.yml/runs?branch=master&status=success&per_page=1"
    const val RELEASES_URL = "https://github.com/Mahmud0808/PixelLauncherEnhanced/releases"
    const val DEV_BUILDS_URL = "https://t.me/DrDsProjectsChat/267103"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 7301
    private const val JOB_ID = 7301
    private const val UPDATES_DIR = "updates"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val TIMEOUT_MS = 15_000
    private const val MIN_CHECK_MS = 1200L
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_STEP = 128 * 1024L
    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(12)
    private val CHECK_FLEX = TimeUnit.HOURS.toMillis(2)
    private val LAUNCH_STALE_AFTER = TimeUnit.HOURS.toMillis(6)
    private val VERSION_PATTERN = Regex("\\d+(\\.\\d+)*")
    private val DEV_PATTERN = Regex("Dev(\\d+)", RegexOption.IGNORE_CASE)

    val isDevBuild: Boolean = BuildConfig.DEBUG

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var checkedThisSession = false
    private var cachedChangelog: List<ReleaseInfo>? = null
    private var latestDev: DevBuild? = null

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    val autoCheckEnabled: Boolean
        get() = !isDevBuild && RPrefs.getBoolean(UPDATE_AUTO_CHECK, true)

    val notificationsEnabled: Boolean
        get() = autoCheckEnabled && RPrefs.getBoolean(UPDATE_NOTIFY, false)

    val lastCheck: Long
        get() = RPrefs.getLong(UPDATE_LAST_CHECK, 0L)

    val installedVersion: String
        get() = BuildConfig.VERSION_NAME

    val installedDevNumber: Int?
        get() = DEV_PATTERN.find(installedVersion)?.groupValues?.get(1)?.toIntOrNull()

    fun cachedRelease(): ReleaseInfo? = ReleaseInfo.fromJson(RPrefs.getString(UPDATE_CACHE, null))

    fun availableUpdate(): ReleaseInfo? = cachedRelease()?.takeIf { isNewer(it.version) }

    fun restore(context: Context) {
        if (mutableState.value != State.Idle) return
        val release = cachedRelease() ?: return
        mutableState.value = resolve(context, release, latestDev)
    }

    private fun resolve(context: Context, release: ReleaseInfo, dev: DevBuild?): State = when {
        isNewer(release.version) ->
            downloadedFile(context, release)?.let { State.Ready(release, it) } ?: State.Available(release)

        dev != null && isNewerDev(dev.number) -> State.DevAvailable(dev)
        else -> State.UpToDate(release)
    }

    private fun isNewerDev(number: Int): Boolean = installedDevNumber?.let { number > it } ?: false

    fun isNewer(version: String): Boolean = compare(version) > 0

    fun isInstalled(version: String): Boolean = compare(version) == 0

    private fun compare(version: String): Int {
        val latest = version.numbers()
        val installed = installedVersion.numbers()
        for (i in 0 until maxOf(latest.size, installed.size)) {
            val a = latest.getOrElse(i) { 0 }
            val b = installed.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun String.numbers(): List<Int> =
        VERSION_PATTERN.find(this)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    suspend fun check(context: Context, manual: Boolean = false): ReleaseInfo? {
        val current = mutableState.value
        if (current is State.Downloading) return current.release
        mutableState.value = State.Checking

        val started = SystemClock.elapsedRealtime()
        val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() }
        if (release != null && isDevBuild && !isNewer(release.version)) {
            latestDev = withContext(Dispatchers.IO) {
                runCatching { DevBuild.fromGitHub(fetch(DEV_RUNS_URL)) }.getOrNull()
            } ?: latestDev
        }
        if (manual) delay(MIN_CHECK_MS - (SystemClock.elapsedRealtime() - started))
        if (release == null) {
            mutableState.value = State.Failed(Error.CHECK, cachedRelease())
            return null
        }

        RPrefs.putString(UPDATE_CACHE, release.toJson())
        RPrefs.putLong(UPDATE_LAST_CHECK, System.currentTimeMillis())
        mutableState.value = resolve(context, release, latestDev)
        cleanup(context)
        return release
    }

    private fun fetchLatest(): ReleaseInfo =
        ReleaseInfo.fromGitHub(fetch(API_URL)) ?: throw IOException("No APK in release")

    suspend fun changelog(): List<ReleaseInfo>? {
        cachedChangelog?.let { return it }
        val releases = withContext(Dispatchers.IO) {
            runCatching { ReleaseInfo.listFromGitHub(fetch(CHANGELOG_URL)) }.getOrNull()
        } ?: return null
        cachedChangelog = releases
        return releases
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PLEnhanced/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, release: ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        downloadJob = scope.launch {
            mutableState.value = State.Downloading(release, 0L, release.size)
            val target = File(updatesDir(appContext), release.apkName)
            val partial = File(target.path + ".part")
            val result = runCatching {
                updatesDir(appContext).listFiles()?.filter { it != target && it != partial }?.forEach { it.delete() }
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", "PLEnhanced/${BuildConfig.VERSION_NAME}")
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                    var downloaded = 0L
                    var reported = 0L
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (downloaded - reported >= PROGRESS_STEP) {
                                    reported = downloaded
                                    mutableState.value = State.Downloading(release, downloaded, total)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (!partial.renameTo(target)) throw IOException("Rename failed")
                target
            }

            val file = result.getOrNull()
            if (file == null) {
                partial.delete()
                if (result.exceptionOrNull() !is CancellationException) {
                    mutableState.value = State.Failed(Error.DOWNLOAD, release)
                }
                return@launch
            }
            if (!appContext.isSameSigner(file)) {
                file.delete()
                mutableState.value = State.Failed(Error.SIGNATURE, release)
                return@launch
            }
            mutableState.value = State.Ready(release, file)
        }
    }

    fun cancelDownload() {
        val current = mutableState.value as? State.Downloading ?: return
        downloadJob?.cancel()
        downloadJob = null
        mutableState.value = State.Available(current.release)
    }

    fun cleanup(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (downloadJob?.isActive == true) return@launch
            val keep = availableUpdate()?.takeUnless { isSkipped(it) }?.let { File(updatesDir(appContext), it.apkName) }
            updatesDir(appContext).listFiles()?.filter { it != keep }?.forEach { it.delete() }
        }
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }

    private fun downloadedFile(context: Context, release: ReleaseInfo): File? =
        File(updatesDir(context), release.apkName).takeIf { it.isFile && (release.size <= 0 || it.length() == release.size) }

    @Suppress("DEPRECATION")
    private fun Context.isSameSigner(file: File): Boolean = runCatching {
        val archive = packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        if (archive.packageName != packageName) return false
        val installed = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val incoming = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val current = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        !incoming.isNullOrEmpty() && incoming == current
    }.getOrDefault(false)

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.debugreports", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun skip(context: Context, release: ReleaseInfo) {
        RPrefs.putString(UPDATE_SKIPPED, release.version)
        if ((mutableState.value as? State.Ready)?.release == release) mutableState.value = State.Available(release)
        cleanup(context)
    }

    fun isSkipped(release: ReleaseInfo) = RPrefs.getString(UPDATE_SKIPPED, null) == release.version

    suspend fun checkOnLaunch(context: Context) {
        if (!autoCheckEnabled || checkedThisSession) return
        checkedThisSession = true
        if (System.currentTimeMillis() - lastCheck > LAUNCH_STALE_AFTER) check(context)
    }

    fun pendingUpdate(): ReleaseInfo? = availableUpdate()?.takeUnless { isSkipped(it) }

    suspend fun checkInBackground(context: Context) {
        val release = check(context) ?: return
        if (!isNewer(release.version) || isSkipped(release)) return
        if (RPrefs.getString(UPDATE_NOTIFIED, null) == release.version) return
        if (notify(context, release)) RPrefs.putString(UPDATE_NOTIFIED, release.version)
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!notificationsEnabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        runCatching {
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(CHECK_INTERVAL, CHECK_FLEX)
                    .setPersisted(true)
                    .build()
            )
        }
    }

    fun needsNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return !NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    }

    fun shouldAskNotifications(): Boolean =
        autoCheckEnabled && !RPrefs.getBoolean(UPDATE_NOTIFICATION_ASKED, false)

    fun setNotifications(context: Context, enabled: Boolean) {
        RPrefs.putBoolean(UPDATE_NOTIFICATION_ASKED, true)
        RPrefs.putBoolean(UPDATE_NOTIFY, enabled)
        schedule(context)
    }

    private fun notify(context: Context, release: ReleaseInfo): Boolean {
        if (needsNotificationPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, release.version))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    fun clearNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
