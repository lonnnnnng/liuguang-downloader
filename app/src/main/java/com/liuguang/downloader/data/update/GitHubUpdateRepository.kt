package com.liuguang.downloader.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

class GitHubUpdateRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build()
) {
    private val appContext = context.applicationContext

    suspend fun getLatestRelease(): UpdateRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "LiuguangDownloader-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("检查更新失败：HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            GitHubReleaseParser.parse(body)
        }
    }

    suspend fun downloadAndVerify(
        release: UpdateRelease,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        GitHubReleaseParser.requireAllowedUrl(release.assetUrl)
        require(release.assetSize in 1..MAX_APK_BYTES) { "安装包大小异常" }

        val updateDir = File(appContext.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
        val partialFile = File(updateDir, "liuguang-update.apk.part")
        val apkFile = File(updateDir, "liuguang-update.apk")
        partialFile.delete()
        apkFile.delete()

        try {
            val response = executeFollowingTrustedRedirects(release.assetUrl)
            response.use {
                if (!it.isSuccessful) throw IOException("下载安装包失败：HTTP ${it.code}")
                val body = it.body ?: throw IOException("安装包响应为空")
                val responseLength = body.contentLength()
                if (responseLength > MAX_APK_BYTES) throw IOException("安装包超过 250 MB")

                body.byteStream().use { input ->
                    partialFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > MAX_APK_BYTES) throw IOException("安装包超过 250 MB")
                            output.write(buffer, 0, count)
                            val total = when {
                                responseLength > 0 -> responseLength
                                release.assetSize > 0 -> release.assetSize
                                else -> 0L
                            }
                            if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            verifySha256(partialFile, release.sha256)
            verifyApkIdentity(partialFile)
            if (!partialFile.renameTo(apkFile)) throw IOException("无法保存安装包")
            onProgress(1f)
            apkFile
        } catch (error: Throwable) {
            partialFile.delete()
            apkFile.delete()
            throw error
        }
    }

    private fun executeFollowingTrustedRedirects(initialUrl: String): okhttp3.Response {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            GitHubReleaseParser.requireAllowedUrl(currentUrl)
            val request = Request.Builder()
                .url(currentUrl)
                .header("Accept", "application/vnd.android.package-archive")
                .header("User-Agent", "LiuguangDownloader-Android")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location")
            response.close()
            if (redirectCount == MAX_REDIRECTS || location.isNullOrBlank()) {
                throw IOException("安装包重定向次数过多")
            }
            currentUrl = URI(currentUrl).resolve(location).toString()
        }
        throw IOException("安装包重定向失败")
    }

    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("安装包 SHA-256 校验失败")
    }

    private fun verifyApkIdentity(file: File) {
        val packageManager = appContext.packageManager
        val archiveInfo = packageInfo(packageManager, file.absolutePath)
            ?: throw IOException("无法读取安装包信息")
        if (archiveInfo.packageName != appContext.packageName) throw IOException("安装包应用标识不匹配")

        val installedInfo = installedPackageInfo(packageManager)
        if (longVersionCode(archiveInfo) <= longVersionCode(installedInfo)) {
            throw IOException("安装包版本不高于当前版本")
        }

        // author: long - 在线更新只接受与当前安装包同一证书签名的 APK，避免 Release 资产被替换后获得应用身份。
        val archiveSigners = signerDigests(archiveInfo)
        val installedSigners = signerDigests(installedInfo)
        if (archiveSigners.isEmpty() || archiveSigners != installedSigners) {
            throw IOException("安装包签名与当前应用不一致")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, archivePath: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return packageManager.getPackageArchiveInfo(archivePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(packageManager: PackageManager): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(appContext.packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    companion object {
        const val UPDATE_DIRECTORY = "update-apk"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/lonnnnnng/liuguang-downloader/releases/latest"
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
    }
}

internal object GitHubReleaseParser {
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L

    fun parse(json: String): UpdateRelease {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
            throw IOException("最新版本不是正式发布版")
        }
        val tagName = root.optString("tag_name").trim()
        val versionName = tagName.removePrefix("v")
        if (versionName.isBlank()) throw IOException("发布版本号无效")

        val assets = root.optJSONArray("assets") ?: throw IOException("发布页没有安装包")
        var selected: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.lowercase(Locale.ROOT).endsWith(".apk")) {
                selected = asset
                break
            }
        }
        val asset = selected ?: throw IOException("发布页没有 APK 安装包")
        val assetName = asset.optString("name")
        val assetUrl = asset.optString("browser_download_url")
        val assetSize = asset.optLong("size", -1L)
        val digest = asset.optString("digest")
        val sha256 = digest.substringAfter("sha256:", missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (!sha256.matches(Regex("[0-9a-f]{64}"))) throw IOException("发布页缺少有效的 SHA-256 摘要")
        requireAllowedUrl(assetUrl)
        if (assetSize !in 1..MAX_APK_BYTES) throw IOException("安装包大小异常")

        return UpdateRelease(
            versionName = versionName,
            title = root.optString("name").ifBlank { "流光下载器 $tagName" },
            notes = root.optString("body"),
            assetName = assetName,
            assetUrl = assetUrl,
            assetSize = assetSize,
            sha256 = sha256
        )
    }

    fun requireAllowedUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw IOException("安装包地址无效")
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val allowedHost = host == "github.com" || host.endsWith(".githubusercontent.com")
        if (uri.scheme != "https" || !allowedHost) throw IOException("安装包地址不受信任")
    }

}
