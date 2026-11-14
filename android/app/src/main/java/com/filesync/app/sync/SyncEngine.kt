package com.filesync.app.sync

import com.filesync.app.api.PutStatus
import com.filesync.app.api.RemoteFile
import com.filesync.app.api.ServerApi
import com.filesync.app.files.LocalEntry
import com.filesync.app.files.LocalFiles
import com.filesync.app.util.sha256Hex

data class ConflictInfo(
    val rel: String,
    val localSize: Long,
    val localSha: String,
    val remoteSize: Long,
    val remoteSha: String,
    val remoteModTime: String,
)

/**
 * 三方比较同步引擎，决策表与 Go CLI 一致：
 * 本地/服务器/基线比较 → 上传、下载、无变化或冲突（由 ask 决定保留版本，绝不静默覆盖）。
 */
class SyncEngine(
    private val local: LocalFiles,
    private val server: ServerApi,
    private val state: StateStore,
    private val log: (String) -> Unit = {},
    private val ask: suspend (ConflictInfo) -> String,
) {
    data class Stats(
        val uploaded: Int,
        val downloaded: Int,
        val unchanged: Int,
        val resolved: Int,
        val skipped: Int,
        val warnings: Int,
        val cleaned: Int,
        val failed: Int,
    )

    private class Ctx(
        val state: StateStore,
        val st: MutableMap<String, StateEntry>,
    ) {
        var dirty = false
        var uploaded = 0
        var downloaded = 0
        var unchanged = 0
        var resolved = 0
        var skipped = 0
        var warnings = 0
        var cleaned = 0
        var failed = 0

        fun record(rel: String, sha: String, size: Long) {
            val cur = st[rel]
            if (cur?.sha256 == sha && cur.size == size) return
            st[rel] = StateEntry(sha, size)
            dirty = true
        }

        fun save() {
            if (dirty) {
                state.save(st)
                dirty = false
            }
        }
    }

    suspend fun sync(filter: ((String) -> Boolean)? = null): Stats {
        val ctx = Ctx(state, state.load().toMutableMap())
        val locals = local.list().associateBy { it.path }
        val remotes = server.ls().associateBy { it.path }
        val union = sortedSetOf<String>()
        union.addAll(locals.keys)
        union.addAll(remotes.keys)
        union.addAll(ctx.st.keys)
        for (rel in union) {
            if (rel == ".filesync.json") continue
            if (filter != null && !filter(rel)) continue
            try {
                handlePath(ctx, rel, locals[rel], remotes[rel], ctx.st[rel])
            } catch (e: Exception) {
                ctx.failed++
                log("失败: $rel: ${e.message}")
            }
        }
        ctx.save()
        return Stats(ctx.uploaded, ctx.downloaded, ctx.unchanged, ctx.resolved, ctx.skipped, ctx.warnings, ctx.cleaned, ctx.failed)
    }

    private suspend fun handlePath(ctx: Ctx, rel: String, le: LocalEntry?, re: RemoteFile?, base: StateEntry?) {
        if (le != null && le.ambiguous) {
            ctx.failed++
            log("失败: 同名歧义（目录中存在多个同名条目），已跳过: $rel")
            return
        }
        when {
            le != null && re != null -> handlePresent(ctx, rel, le, re, base)
            le != null -> {
                if (base == null) {
                    uploadNew(ctx, rel, le)
                } else {
                    ctx.warnings++
                    log("注意: 服务器已不存在，本地保留（不传播删除）: $rel")
                }
            }
            re != null -> {
                if (base == null) {
                    download(ctx, rel, re)
                    ctx.downloaded++
                    log("下载 $rel")
                } else {
                    ctx.warnings++
                    log("注意: 本地已删除，服务器保留（不传播删除）: $rel")
                }
            }
            else -> {
                if (base != null) {
                    ctx.st.remove(rel)
                    ctx.dirty = true
                    ctx.cleaned++
                    log("清理记录 $rel（两端均已不存在）")
                }
            }
        }
    }

    private suspend fun handlePresent(ctx: Ctx, rel: String, le: LocalEntry, re: RemoteFile, base: StateEntry?) {
        val lh = local.hash(le) ?: throw Exception("本地文件不可读")
        // 决策入口：两端内容一致 → 无论基线是什么状态都直接收敛（自愈过期/损坏的基线）
        if (lh == re.sha256) {
            ctx.record(rel, lh, le.size)
            ctx.unchanged++
            log("已一致 $rel")
            return
        }
        val b = base
        if (b == null) {
            // 首次遇到两端内容不同 → 按冲突处理
            conflictChoice(ctx, rel, le, lh, re)
            return
        }
        // 此处必然 lh != re，两端至少有一端相对基线发生了变化
        val localChanged = lh != b.sha256
        val serverChanged = re.sha256 != b.sha256
        when {
            localChanged && !serverChanged -> {
                // 基线哈希必须与实际上传的字节同源，否则下次同步永远判不一致
                val data = local.read(le) ?: throw Exception("本地文件不可读")
                val sum = sha256Hex(data)
                when (server.put(rel, data, true, b.sha256)) {
                    PutStatus.CREATED -> {
                        ctx.record(rel, sum, data.size.toLong())
                        ctx.uploaded++
                        log("上传 $rel")
                    }
                    PutStatus.CONFLICT -> {
                        ctx.skipped++
                        log("冲突(上传时服务器内容已变化，本轮跳过): $rel")
                    }
                    PutStatus.ERROR -> throw Exception("上传失败")
                }
            }
            serverChanged && !localChanged -> {
                download(ctx, rel, re)
                ctx.downloaded++
                log("下载 $rel")
            }
            else -> conflictChoice(ctx, rel, le, lh, re)
        }
    }

    private fun uploadNew(ctx: Ctx, rel: String, le: LocalEntry) {
        val data = local.read(le) ?: throw Exception("本地文件不可读")
        val sum = sha256Hex(data)
        when (server.put(rel, data, false, null)) {
            PutStatus.CREATED -> {
                ctx.record(rel, sum, data.size.toLong())
                ctx.uploaded++
                log("上传 $rel")
            }
            PutStatus.CONFLICT -> {
                ctx.skipped++
                log("冲突(服务器已出现同名文件，本轮跳过): $rel")
            }
            PutStatus.ERROR -> throw Exception("上传失败")
        }
    }

    private fun download(ctx: Ctx, rel: String, re: RemoteFile) {
        val dl = server.get(rel) ?: throw Exception("服务器文件不存在")
        if (!local.write(rel, dl.first)) throw Exception("本地写入失败")
        ctx.record(rel, dl.second, re.size)
    }

    private suspend fun conflictChoice(ctx: Ctx, rel: String, le: LocalEntry, lh: String, re: RemoteFile) {
        ctx.save()
        when (ask(ConflictInfo(rel, le.size, lh, re.size, re.sha256, re.modTime))) {
            "local" -> {
                val data = local.read(le) ?: throw Exception("本地文件不可读")
                if (server.put(rel, data, true, null) != PutStatus.CREATED) throw Exception("上传被拒绝")
                ctx.record(rel, sha256Hex(data), data.size.toLong())
                ctx.resolved++
                log("冲突解决(保留本地) $rel")
            }
            "remote" -> {
                download(ctx, rel, re)
                ctx.resolved++
                log("冲突解决(保留远端) $rel")
            }
            else -> {
                ctx.skipped++
                log("冲突未解决(已跳过) $rel")
            }
        }
    }
}
