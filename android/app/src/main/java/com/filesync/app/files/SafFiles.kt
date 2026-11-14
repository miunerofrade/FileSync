package com.filesync.app.files

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.filesync.app.sync.StateEntry
import com.filesync.app.sync.StateStore
import com.filesync.app.util.sha256Hex
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

private val tempPrefixes = listOf(".fsync-tmp-", ".fsync-get-")

private fun isTempName(name: String) = tempPrefixes.any { it.isNotEmpty() && name.startsWith(it) }

/** SAF 目录树上的同步目录实现。 */
class SafFiles(private val context: Context, private val treeUri: Uri) : LocalFiles {

    private val resolver = context.contentResolver

    private fun rootDoc(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri) ?: throw Exception("同步目录不可用")

    private fun docFor(rel: String): DocumentFile? {
        val parts = rel.split("/")
        var dir = rootDoc()
        for (i in 0 until parts.size - 1) {
            dir = dir.findFile(parts[i])?.takeIf { it.isDirectory } ?: return null
        }
        return dir.findFile(parts.last())?.takeIf { it.isFile }
    }

    private fun dirFor(relDir: String, create: Boolean): DocumentFile? {
        var dir = rootDoc()
        if (relDir.isEmpty()) return dir
        for (part in relDir.split("/")) {
            if (part.isEmpty()) continue
            val next = dir.findFile(part)
            dir = when {
                next?.isDirectory == true -> next
                next == null && create -> dir.createDirectory(part) ?: return null
                else -> return null
            }
        }
        return dir
    }

    override fun list(): List<LocalEntry> {
        val out = ArrayList<LocalEntry>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                when {
                    f.isDirectory -> walk(f, if (prefix.isEmpty()) name else "$prefix/$name")
                    f.isFile -> {
                        if (isTempName(name)) continue
                        if (prefix.isEmpty() && name == ".filesync.json") continue
                        out.add(LocalEntry(if (prefix.isEmpty()) name else "$prefix/$name", f.length()))
                    }
                }
            }
        }
        walk(rootDoc(), "")
        return out
    }

    /** 浏览界面用的单层列表。 */
    fun listDir(relDir: String): List<DirEntry> {
        val dir = if (relDir.isEmpty()) rootDoc() else dirFor(relDir, false) ?: return emptyList()
        return dir.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            if (isTempName(name)) return@mapNotNull null
            when {
                f.isDirectory -> DirEntry(name, true, 0)
                f.isFile -> DirEntry(name, false, f.length())
                else -> null
            }
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun uriFor(rel: String): Uri? = docFor(rel)?.uri

    override fun hash(rel: String): String? = try {
        val df = docFor(rel) ?: return null
        resolver.openInputStream(df.uri)?.use { ins ->
            val md = MessageDigest.getInstance("SHA-256")
            pump(ins, md)
            sha256Hex(md.digest())
        }
    } catch (_: Exception) {
        null
    }

    override fun read(rel: String): ByteArray? = try {
        docFor(rel)?.let { df -> resolver.openInputStream(df.uri)?.use { it.readBytes() } }
    } catch (_: Exception) {
        null
    }

    override fun write(rel: String, data: ByteArray): Boolean {
        return try {
            val parts = rel.split("/")
            val fileName = parts.last()
            val dir = dirFor(if (parts.size == 1) "" else parts.dropLast(1).joinToString("/"), true) ?: return false
            // 临时文件以时间戳命名（唯一，不会被去重改名）；内容完整落盘后，
            // 先删旧目标再改名——改名目标不存在，必然精确生效。
            val tmpName = ".fsync-tmp-" + System.currentTimeMillis() + "-" + fileName
            val tmp = dir.createFile(mimeOf(fileName), tmpName) ?: return false
            resolver.openOutputStream(tmp.uri)?.use { it.write(data) } ?: return false
            dir.findFile(fileName)?.delete()
            try {
                DocumentsContract.renameDocument(resolver, tmp.uri, fileName) != null
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 清理进程被杀遗留的临时文件。 */
    fun cleanupTemps() {
        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) walk(f)
                else if (isTempName(name)) f.delete()
            }
        }
        walk(rootDoc())
    }

    private fun pump(ins: InputStream, md: MessageDigest) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown", "txt", "log", "json" -> "text/plain"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}

/** 基线保存在 SharedPreferences（commit 原子写，中断不损坏）。 */
class PrefsStateStore(private val prefs: SharedPreferences, private val key: String) : StateStore {

    override fun load(): Map<String, StateEntry> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = HashMap<String, StateEntry>()
            for (k in obj.keys()) {
                val o = obj.getJSONObject(k)
                out[k] = StateEntry(o.getString("sha256"), o.getLong("size"), o.optString("syncedAt"))
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun save(files: Map<String, StateEntry>) {
        val obj = JSONObject()
        for ((k, v) in files) {
            obj.put(
                k,
                JSONObject().put("sha256", v.sha256).put("size", v.size).put("syncedAt", v.syncedAt),
            )
        }
        prefs.edit().putString(key, obj.toString()).commit()
    }
}
