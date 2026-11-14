package com.filesync.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * GitHub Release 自动更新：检查 → 下载到缓存 → 调起系统安装器。
 * Android 8+ 对侧载安装的确认由系统强制，无法绕过；"安装未知应用"授权每个 App 只需一次。
 */
object Updater {

    data class Info(val tag: String, val apkUrl: String, val notes: String)

    private const val API = "https://api.github.com/repos/miunerofrade/FileSync/releases/latest"

    /** 版本串 "v1.2.3" → [1,2,3]，逐段数值比较。 */
    private fun parseVersion(s: String): List<Int> =
        s.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

    private fun isNewer(remote: String, current: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 查询最新 Release。已是最新返回 null；失败抛异常（调用方区分展示）。
     * 优先走自有服务端代理（手机共享网络对 GitHub API 极易 403 限流），
     * 服务端无此接口（旧版本）或失败时回退直连 GitHub。
     * refresh=true 强制服务端绕过缓存（手动检查用）。
     */
    fun check(currentVersion: String, serverUrl: String, token: String, refresh: Boolean): Info? {
        val body = try {
            httpGet("$serverUrl/api/update-check" + if (refresh) "?refresh=1" else "", token)
        } catch (_: Exception) {
            null
        } ?: httpGet(API, null)
        val o = JSONObject(body)
        val tag = o.optString("tag_name")
        if (!isNewer(tag, currentVersion)) return null
        val assets = o.optJSONArray("assets") ?: throw RuntimeException("Release 无附件")
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                apkUrl = a.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isEmpty()) throw RuntimeException("Release 无 APK 附件")
        val notes = o.optString("body").take(400)
        return Info(tag, apkUrl, notes)
    }

    private fun httpGet(url: String, token: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "FileSync-App")
        if (!token.isNullOrBlank()) {
            c.setRequestProperty("Authorization", "Bearer $token")
        }
        if (c.responseCode != 200) {
            throw RuntimeException("HTTP ${c.responseCode}")
        }
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 下载 APK（进度 0-100），失败返回 null。 */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit): File? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return null
            val total = c.contentLengthLong
            dest.parentFile?.mkdirs()
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (total > 0 && dest.length() != total) null else dest
        } catch (_: Exception) {
            null
        }
    }

    /** 调起系统安装器。返回 false 表示需先授权"安装未知应用"（已跳转授权页）。 */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
        return true
    }
}
