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
        fun walk(dir: DocumentFile, prefix: String, parentUri: Uri) {
            val children = dir.listFiles().filter { it.name != null }
            android.util.Log.w(
                "FileSyncDiag",
                "listDir parent=${parentUri.lastPathSegment} entries=${children.size}",
            )
            for ((name, group) in groups(children)) {
                if (isTempName(name)) continue
                if (prefix.isEmpty() && (name == ".filesync.json" || name == "filesync-client.json")) continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                val files = group.filter { it.isFile }
                val dirs = group.filter { it.isDirectory }
                when {
                    // 同名多个文档：SAF 无法确定该操作哪一个，标记歧义跳过
                    files.size > 1 || dirs.size > 1 -> {
                        android.util.Log.w(
                            "FileSyncDiag",
                            "ambiguous rel=$rel docs=${files.size} dirs=${dirs.size}",
                        )
                        out.add(LocalEntry(rel, 0, null, ambiguous = true))
                    }
                    files.size == 1 -> {
                        val f = files[0]
                        android.util.Log.w(
                            "FileSyncDiag",
                            "entry rel=$rel docId=${f.uri.lastPathSegment} size=${f.length()}",
                        )
                        out.add(LocalEntry(rel, f.length(), f.uri))
                    }
                    dirs.size == 1 -> walk(dirs[0], rel, dirs[0].uri)
                }
            }
        }
        walk(rootDoc(), "", rootDoc().uri)
        return out
    }

    private fun groups(children: List<DocumentFile>): Map<String, List<DocumentFile>> =
        children.groupBy { it.name!! }

    /** 浏览界面用的单层列表。 */
    fun listDir(relDir: String): List<DirEntry> {
        val dir = if (relDir.isEmpty()) rootDoc() else dirFor(relDir, false) ?: return emptyList()
        val groups = dir.listFiles().filter { it.name != null }.groupBy { it.name!! }
        return groups.map { (name, group) ->
            if (isTempName(name)) return@map null
            if (name == "filesync-client.json") return@map null
            val f = group.first()
            val ambiguous = group.size > 1
            when {
                f.isDirectory -> DirEntry(name, true, 0, ambiguous)
                f.isFile -> DirEntry(name, false, f.length(), ambiguous)
                else -> null
            }
        }.filterNotNull()
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** 预览/手动操作用的单文件定位，同样带歧义检测。 */
    fun entryFor(rel: String): LocalEntry? {
        val parts = rel.split("/")
        val fileName = parts.last()
        val dir = dirFor(if (parts.size == 1) "" else parts.dropLast(1).joinToString("/"), false) ?: return null
        val group = dir.listFiles().filter { it.name == fileName && it.isFile }
        return when {
            group.size > 1 -> LocalEntry(rel, 0, null, ambiguous = true)
            group.size == 1 -> LocalEntry(rel, group[0].length(), group[0].uri)
            else -> null
        }
    }

    fun uriFor(rel: String): Uri? =
        entryFor(rel)?.takeIf { !it.ambiguous }?.handle as? Uri

    override fun read(e: LocalEntry): ByteArray? = try {
        val uri = e.handle as? Uri ?: return null
        val b1 = resolver.openInputStream(uri)?.use { it.readBytes() }
        val b2 = resolver.openInputStream(uri)?.use { it.readBytes() }
        // 一致性诊断：同一 URI 连续两次读取的字节对比（只打标识与哈希，不打内容）
        android.util.Log.w(
            "FileSyncDiag",
            "read ${e.path} docId=${uri.lastPathSegment} len1=${b1?.size} sha1=${b1?.let { sha256Hex(it) }?.take(12)} " +
                "len2=${b2?.size} sha2=${b2?.let { sha256Hex(it) }?.take(12)}",
        )
        b1
    } catch (_: Exception) {
        null
    }

    override fun write(rel: String, data: ByteArray): Boolean {
        return try {
            val parts = rel.split("/")
            val fileName = parts.last()
            val dir = dirFor(if (parts.size == 1) "" else parts.dropLast(1).joinToString("/"), true) ?: return false
            // 目标名歧义（同名多文档）时无法确定删哪个，拒绝写入
            val sameName = dir.listFiles().filter { it.name == fileName && it.isFile }
            if (sameName.size > 1) return false
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
