package com.ftpflix.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.core.net.toUri
import com.ftpflix.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class AppUpdateStatus {
    IDLE, CHECKING, UP_TO_DATE, AVAILABLE,
    DOWNLOADING, READY_TO_INSTALL, NEEDS_INSTALL_PERMISSION, INSTALLING, ERROR
}

data class AppUpdateState(
    val status: AppUpdateStatus = AppUpdateStatus.IDLE,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadSizeBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val message: String? = null,
) {
    val downloadProgress: Float
        get() = if (downloadSizeBytes > 0) (downloadedBytes.toFloat() / downloadSizeBytes).coerceIn(0f, 1f) else 0f
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** Picks the installable APK: ABI-specific first, then universal, then any. */
    fun apkAsset(abi: String? = null): GitHubAsset? {
        val apks = assets.filter { (name, _, _, contentType) ->
            name.endsWith(".apk", ignoreCase = true) &&
                (contentType.isBlank() || contentType.contains("android", ignoreCase = true))
        }
        abi?.let { a -> apks.firstOrNull { it.name.contains(a, ignoreCase = true) } }?.let { return it }
        apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
        return apks.firstOrNull()
    }
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("content_type") val contentType: String = "",
)

// GitHub Releases OTA updater: check, download via DownloadManager, verify, install.
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val downloadManager get() = context.getSystemService(DownloadManager::class.java)

    private var pollJob: Job? = null
    private var downloadId: Long = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
    private var releaseUrl: String? = null

    /** True while a checked release is being downloaded or awaits installation. */
    private val isBusy: Boolean
        get() = _state.value.status in setOf(
            AppUpdateStatus.DOWNLOADING,
            AppUpdateStatus.READY_TO_INSTALL,
            AppUpdateStatus.NEEDS_INSTALL_PERMISSION,
            AppUpdateStatus.INSTALLING
        )

    /** Checks GitHub for a newer release; also resumes/cleans up any pending download. */
    fun check(auto: Boolean = false) {
        if (_state.value.status in setOf(
                AppUpdateStatus.CHECKING, AppUpdateStatus.DOWNLOADING, AppUpdateStatus.INSTALLING
            )
        ) return
        if (auto && ((!isBusy) && (_state.value.status != AppUpdateStatus.IDLE))) return
        if (BuildConfig.GITHUB_OWNER.isBlank() || BuildConfig.GITHUB_REPO.isBlank()) {
            _state.update { it.copy(status = AppUpdateStatus.ERROR, message = "Update source is not configured.") }
            return
        }
        _state.update { it.copy(status = AppUpdateStatus.CHECKING, message = null) }
        scope.launch {
            resumePendingDownload()
            val release = fetchLatestRelease()
            release.fold(
                onSuccess = ::onReleaseChecked,
            ) { e ->
                if (!isBusy) {
                    _state.update {
                        it.copy(status = AppUpdateStatus.ERROR, message = e.message ?: "Couldn't reach the update server.")
                    }
                }
            }
        }
    }

    fun download() {
        if (_state.value.status != AppUpdateStatus.AVAILABLE) return
        val url = releaseUrl ?: return
        val dir = context.getExternalFilesDir(null)
        if (dir == null) {
            _state.update {
                it.copy(status = AppUpdateStatus.ERROR, message = "Storage is unavailable for the download.")
            }
            return
        }
        cleanApkFiles()
        val request = DownloadManager.Request(url.toUri())
            .setTitle("FTPFlix ${_state.value.latestVersion.orEmpty()}")
            .setDescription("Downloading app update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "$UPDATE_DIR/$APK_FILE")
        downloadId = downloadManager.enqueue(request)
        prefs.edit {
            putLong(KEY_DOWNLOAD_ID, downloadId)
            putString(KEY_VERSION, _state.value.latestVersion)
            putString(KEY_URL, url)
        }
        _state.update { it.copy(status = AppUpdateStatus.DOWNLOADING, downloadedBytes = 0, message = null) }
        startPolling()
    }

    // Cancel a running download or discard a finished one; removes the file.
    fun cancelDownload() {
        val status = _state.value.status
        if ((status != AppUpdateStatus.DOWNLOADING) && (status != AppUpdateStatus.READY_TO_INSTALL)) return
        pollJob?.cancel()
        if (downloadId > 0) runCatching { downloadManager.remove(downloadId) }
        apkFile()?.delete()
        val releaseKnown = releaseUrl != null
        clearPendingDownload()
        _state.update {
            it.copy(
                status = if (releaseKnown) AppUpdateStatus.AVAILABLE else AppUpdateStatus.IDLE,
                downloadedBytes = 0,
                message = if (status == AppUpdateStatus.DOWNLOADING) "Download cancelled." else "Download discarded."
            )
        }
    }

    /** User action: installs the downloaded APK (requests the unknown-sources permission first if needed). */
    fun install() {
        if (_state.value.status !in setOf(
                AppUpdateStatus.READY_TO_INSTALL, AppUpdateStatus.NEEDS_INSTALL_PERMISSION
            )
        ) return
        if (!canRequestInstalls()) {
            _state.update { it.copy(status = AppUpdateStatus.NEEDS_INSTALL_PERMISSION) }
            return
        }
        launchInstaller()
    }

    /** Called when the user returns from the installer without the install completing. */
    fun onInstallerClosed() {
        if (_state.value.status == AppUpdateStatus.INSTALLING) {
            _state.update { it.copy(status = AppUpdateStatus.READY_TO_INSTALL, message = null) }
        }
    }

    /** Called when the user returns from the unknown-sources settings screen. */
    fun onInstallPermissionResult() {
        if (_state.value.status != AppUpdateStatus.NEEDS_INSTALL_PERMISSION) return
        if (canRequestInstalls()) {
            launchInstaller()
        } else {
            _state.update {
                it.copy(status = AppUpdateStatus.READY_TO_INSTALL, message = "The install permission is still off.")
            }
        }
    }

    /** Intent that opens this app's "install unknown apps" settings page. */
    fun permissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())

    private fun launchInstaller() {
        val file = apkFile()
        if (file?.exists() != true) {
            clearPendingDownload()
            _state.update {
                it.copy(status = AppUpdateStatus.ERROR, message = "The downloaded update is missing; check again.")
            }
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        _state.update { it.copy(status = AppUpdateStatus.INSTALLING, message = null) }
        runCatching { context.startActivity(intent) }
            .onFailure {
                _state.update {
                    it.copy(status = AppUpdateStatus.READY_TO_INSTALL, message = "Couldn't open the installer.")
                }
            }
    }

    private fun canRequestInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Device ABI (e.g. "arm64-v8a") used to pick the matching APK asset. */
    private fun currentAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull()

    private suspend fun fetchLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val url = "${BuildConfig.GITHUB_RELEASE_API}/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                .build()
            val body = okHttp.newCall(request).execute().use { resp ->
                when {
                    resp.code == 404 -> throw IOException("Update repository was not found.")
                    !resp.isSuccessful -> throw IOException("Update server error (${resp.code}).")
                    else -> resp.body.string()
                }
            }
            Result.success(json.decodeFromString<GitHubRelease>(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun onReleaseChecked(release: GitHubRelease) {
        val apk = release.apkAsset(currentAbi())
        val latest = parseVersion(release.tagName)
        val current = parseVersion(BuildConfig.VERSION_NAME) ?: listOf(0)
        when {
            latest == null -> if (!isBusy) {
                _state.update {
                    it.copy(status = AppUpdateStatus.ERROR, message = "Release tag '${release.tagName}' is not a valid version.")
                }
            }
            apk == null || apk.browserDownloadUrl.isBlank() -> if (!isBusy) {
                _state.update {
                    it.copy(status = AppUpdateStatus.ERROR, message = "The latest release has no APK file.")
                }
            }
            compareVersions(latest, current) <= 0 -> {
                if (!isBusy) {
                    // Older downloads are no longer needed once up to date.
                    cleanApkFiles()
                    clearPendingDownload()
                    _state.update {
                        it.copy(
                            status = AppUpdateStatus.UP_TO_DATE,
                            latestVersion = release.tagName,
                            releaseNotes = release.body?.takeIf { n -> n.isNotBlank() },
                            downloadSizeBytes = apk.size,
                            downloadedBytes = 0,
                            message = null
                        )
                    }
                }
            }
            else -> {
                releaseUrl = apk.browserDownloadUrl
                val notes = release.body?.takeIf { n -> n.isNotBlank() }
                _state.update {
                    when (it.status) {
                        AppUpdateStatus.DOWNLOADING -> it.copy(
                            latestVersion = release.tagName, releaseNotes = notes, downloadSizeBytes = apk.size
                        )
                        AppUpdateStatus.READY_TO_INSTALL, AppUpdateStatus.NEEDS_INSTALL_PERMISSION -> it.copy(
                            releaseNotes = notes, downloadSizeBytes = apk.size
                        )
                        else -> it.copy(
                            status = AppUpdateStatus.AVAILABLE,
                            latestVersion = release.tagName,
                            releaseNotes = notes,
                            downloadSizeBytes = apk.size,
                            downloadedBytes = 0,
                            message = null
                        )
                    }
                }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL)
                if (!queryDownloadStatus()) return@launch
            }
        }
    }

    /** Returns false when polling should stop (finished, failed, or vanished). */
    private fun queryDownloadStatus(): Boolean {
        val cursor = runCatching {
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        }.getOrNull()
        if (cursor == null || !cursor.moveToFirst()) {
            onDownloadFailed("Download was removed.")
            return false
        }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                onDownloadSuccess()
                return false
            }
            DownloadManager.STATUS_FAILED -> {
                onDownloadFailed("Download failed.")
                return false
            }
            else -> _state.update {
                it.copy(
                    status = AppUpdateStatus.DOWNLOADING,
                    downloadedBytes = downloaded,
                    downloadSizeBytes = if (total > 0) total else it.downloadSizeBytes
                )
            }
        }
        return true
    }

    private fun onDownloadSuccess() {
        val file = apkFile()
        val error = when (file) {
            null -> "The downloaded update is missing."
            else -> verifyApk(file)
        }
        if (error != null) {
            file?.delete()
            clearPendingDownload()
            _state.update {
                it.copy(status = AppUpdateStatus.ERROR, message = error, downloadedBytes = 0)
            }
            return
        }
        _state.update {
            it.copy(
                status = AppUpdateStatus.READY_TO_INSTALL,
                downloadedBytes = file?.length() ?: it.downloadedBytes,
                message = null
            )
        }
    }

    private fun onDownloadFailed(message: String) {
        runCatching { downloadManager.remove(downloadId) }
        apkFile()?.delete()
        clearPendingDownload()
        if (_state.value.status == AppUpdateStatus.DOWNLOADING) {
            _state.update {
                it.copy(status = AppUpdateStatus.ERROR, message = message, downloadedBytes = 0)
            }
        }
    }

    /** Zip magic + archive parse + package identity: guards against truncated or foreign files. */
    private fun verifyApk(file: File): String? {
        if (file.length() < MIN_APK_BYTES) return "The downloaded file is incomplete."
        val magic = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(magic) < magic.size) return "The downloaded file is incomplete."
        }
        if (!magic.contentEquals(ZIP_MAGIC)) return "The downloaded file is not a valid APK."
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return "The downloaded file is not a valid APK."
        if (info.packageName != context.packageName) return "The downloaded file is not an FTPFlix update."
        return null
    }

    /** Restores state for a download started in a previous session. */
    private fun resumePendingDownload() {
        if (downloadId <= 0) return
        val cursor = runCatching {
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        }.getOrNull()
        if (cursor == null || !cursor.moveToFirst()) {
            clearPendingDownload()
            return
        }
        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val file = apkFile()
                val error = if (file == null) "The downloaded update is missing." else verifyApk(file)
                if (file != null && error == null) {
                    releaseUrl = prefs.getString(KEY_URL, null)
                    _state.update {
                        it.copy(
                            status = AppUpdateStatus.READY_TO_INSTALL,
                            latestVersion = prefs.getString(KEY_VERSION, null),
                            downloadedBytes = file.length(),
                            message = null
                        )
                    }
                } else {
                    file?.delete()
                    clearPendingDownload()
                }
            }
            DownloadManager.STATUS_FAILED -> onDownloadFailed("Previous download failed.")
            else -> {
                releaseUrl = prefs.getString(KEY_URL, null)
                _state.update {
                    it.copy(
                        status = AppUpdateStatus.DOWNLOADING,
                        latestVersion = prefs.getString(KEY_VERSION, null)
                    )
                }
                startPolling()
            }
        }
    }

    /** Deletes leftover APKs from failed/cancelled/old updates. */
    private fun cleanApkFiles() {
        val dir = File(context.getExternalFilesDir(null), UPDATE_DIR)
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk", ignoreCase = true) && file.name != APK_FILE) file.delete()
        }
    }

    private fun apkFile(): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(File(dir, UPDATE_DIR), APK_FILE)
    }

    private fun clearPendingDownload() {
        prefs.edit { clear() }
        downloadId = -1L
    }

    private companion object {
        const val UPDATE_DIR = "updates"
        const val APK_FILE = "ftpflix-update.apk"
        const val APK_MIME = "application/vnd.android.package-archive"
        private val POLL_INTERVAL = 500.milliseconds
        const val MIN_APK_BYTES = 1024L * 1024
        const val GITHUB_API_VERSION = "2022-11-28"
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_VERSION = "version"
        const val KEY_URL = "url"
    }
}

/** Parses "v1.2.3" / "1.2" into numeric segments; null when the tag is not a plain semver. */
internal fun parseVersion(tag: String): List<Int>? {
    val cleaned = tag.trim().removePrefix("v").removePrefix("V").substringBefore('-')
    if (cleaned.isEmpty()) return null
    val parts = cleaned.split('.')
    if (parts.isEmpty() || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return null
    return parts.map { it.toInt() }
}

internal fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val l = a.getOrElse(i) { 0 }
        val r = b.getOrElse(i) { 0 }
        if (l != r) return l - r
    }
    return 0
}
