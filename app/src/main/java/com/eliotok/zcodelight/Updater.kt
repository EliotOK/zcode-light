package com.eliotok.zcodelight

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 应用内更新：走 GitHub Releases，做法与 codex-lan 对齐——
 * 版本号比对 → 下载 → SHA-256 与包名校验 → 拉起系统安装器。不引入第三方依赖。
 */
object Updater {
    private const val REPO = "EliotOK/zcode-light"

    data class ReleaseInfo(
        val version: String,
        val apkUrl: String,
        val sha256Url: String?,
        val notes: String?,
    )

    sealed class CheckResult {
        data class Available(val release: ReleaseInfo, val expectedSha256: String?) : CheckResult()
        object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    sealed class UpdateUi {
        object Hidden : UpdateUi()
        object Checking : UpdateUi()
        data class Available(val release: ReleaseInfo, val expectedSha256: String?) : UpdateUi()
        data class Progress(val percent: Int) : UpdateUi()
        object UpToDate : UpdateUi()
        data class Error(val message: String) : UpdateUi()
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease()
            if (!isNewer(release.version)) return@withContext CheckResult.UpToDate
            val sha = release.sha256Url?.let {
                fetchText(it).trim().split(Regex("\\s+")).firstOrNull()
            }
            CheckResult.Available(release, sha)
        } catch (e: Exception) {
            CheckResult.Error(e.message ?: "检查更新失败")
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val body = httpGet("https://api.github.com/repos/$REPO/releases/latest")
        val obj = JSONObject(body)
        var apkUrl: String? = null
        var sha256Url: String? = null
        val assets = obj.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) apkUrl = asset.getString("browser_download_url")
            if (name.endsWith(".apk.sha256")) sha256Url = asset.getString("browser_download_url")
        }
        apkUrl ?: throw IOException("Release 中没有 APK")
        return ReleaseInfo(
            version = obj.getString("tag_name").removePrefix("v"),
            apkUrl = apkUrl,
            sha256Url = sha256Url,
            notes = obj.optString("body"),
        )
    }

    fun isNewer(remote: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val remoteParts = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    suspend fun downloadAndVerify(
        context: Context,
        release: ReleaseInfo,
        expectedSha256: String?,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir("apks") ?: context.cacheDir
        val file = File(dir, "zcode-light-update.apk")
        download(release.apkUrl, file, onProgress)
        if (expectedSha256 != null) {
            val actual = sha256Hex(file)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                file.delete()
                throw IOException("SHA-256 校验失败，安装包可能已损坏")
            }
        }
        val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (archiveInfo == null) {
            file.delete()
            throw IOException("APK 无法解析")
        }
        if (archiveInfo.packageName != context.packageName) {
            file.delete()
            throw IOException("包名不匹配：${archiveInfo.packageName}")
        }
        file
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // 部分 ROM 没有该入口；届时安装器会自行提示
        }
    }

    fun launchInstall(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "zcode-light/${BuildConfig.VERSION_NAME}")
        return conn
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
