package com.filesync.app.api

import com.filesync.app.util.sha256Hex
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

class HttpServerApi(baseUrl: String, private val token: String, subDir: String) : ServerApi {

    private val base = baseUrl.trimEnd('/')
    private val sub = subDir.trim().trimEnd('/')

    /** 本地相对路径 → 云端路径（加 subdir 前缀）。 */
    private fun joinSub(rel: String): String =
        if (sub.isEmpty() || rel.isEmpty()) rel else "$sub/$rel"

    private fun open(path: String, query: Map<String, String> = emptyMap()): HttpURLConnection {
        val sb = StringBuilder(base).append(path)
        if (query.isNotEmpty()) {
            sb.append('?')
            sb.append(query.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            })
        }
        return (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 60000
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
    }

    override fun ping(): Boolean = try {
        open("/api/ping").responseCode == 200
    } catch (e: Exception) {
        android.util.Log.w("FileSyncPing", "ping $base failed: ${e::class.java.simpleName}: ${e.message}")
        false
    }

    override fun probe(): Pair<Boolean, String> = try {
        val c = open("/api/ping")
        val code = c.responseCode
        when {
            code == 200 -> true to "ok"
            code == 401 -> false to "HTTP 401（令牌不匹配）"
            else -> false to "HTTP $code"
        }
    } catch (e: Exception) {
        false to "${e::class.java.simpleName}: ${e.message}"
    }

    override fun ls(prefix: String): List<RemoteFile> {
        val full = if (prefix.isEmpty()) sub else joinSub(prefix)
        val c = open("/api/ls", if (full.isEmpty()) emptyMap() else mapOf("prefix" to full))
        if (c.responseCode != 200) throw ServerException("ls 失败 HTTP ${c.responseCode}", c.responseCode)
        val arr = JSONArray(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        val out = ArrayList<RemoteFile>(arr.length())
        val p = if (full.isEmpty()) "" else "$full/"
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val path = o.getString("path").removePrefix(p)
            out.add(RemoteFile(path, o.getLong("size"), o.getString("sha256"), o.optString("mod_time")))
        }
        return out
    }

    override fun get(path: String): Pair<ByteArray, String>? {
        val c = open("/api/get", mapOf("path" to joinSub(path)))
        when (c.responseCode) {
            200 -> {}
            404 -> return null
            else -> throw ServerException("get 失败 HTTP ${c.responseCode}", c.responseCode)
        }
        val want = c.getHeaderField("X-Checksum-SHA256")?.lowercase()
        val data = c.inputStream.use { it.readBytes() }
        val got = sha256Hex(data)
        if (want != null && want.length == 64 && got != want) throw ServerException("SHA-256 校验失败")
        return data to got
    }

    override fun put(path: String, data: ByteArray, overwrite: Boolean, ifMatch: String?): PutStatus {
        val q = HashMap<String, String>()
        q["path"] = joinSub(path)
        if (overwrite) q["overwrite"] = "1"
        if (ifMatch != null) q["if-match"] = ifMatch
        val c = open("/api/put", q)
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("X-Checksum-SHA256", sha256Hex(data))
        c.setFixedLengthStreamingMode(data.size)
        try {
            c.outputStream.use { it.write(data) }
        } catch (_: IOException) {
            // 服务器拒绝请求时不读 body 直接关闭连接，写大文件会在此断开；
            // 尽量取回状态码，取不到按冲突处理（绝不静默覆盖，下轮重新评估）
            return try {
                mapStatus(c.responseCode)
            } catch (_: Exception) {
                PutStatus.CONFLICT
            }
        }
        return try {
            mapStatus(c.responseCode)
        } catch (_: Exception) {
            PutStatus.ERROR
        }
    }

    override fun delete(path: String): Boolean {
        val c = open("/api/delete", mapOf("path" to joinSub(path)))
        c.requestMethod = "DELETE"
        return when (c.responseCode) {
            // 404 视为幂等成功（云端已不存在）
            200, 404 -> {
                c.inputStream.use { it.readBytes() }
                true
            }
            else -> {
                c.errorStream?.use { it.readBytes() }
                throw ServerException("删除失败 HTTP ${c.responseCode}", c.responseCode)
            }
        }
    }

    private fun mapStatus(code: Int): PutStatus = when (code) {
        201 -> PutStatus.CREATED
        409, 412 -> PutStatus.CONFLICT
        else -> PutStatus.ERROR
    }
}
