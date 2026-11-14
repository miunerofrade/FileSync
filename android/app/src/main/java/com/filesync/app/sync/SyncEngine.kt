package com.filesync.app.sync

import com.filesync.app.api.PutStatus
import com.filesync.app.api.RemoteFile
import com.filesync.app.api.ServerApi
import com.filesync.app.files.LocalEntry
import com.filesync.app.files.LocalFiles

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
        when {
            le != null && re != null -> handlePresent(ctx, rel, le, re, base)
            le != null -> {
                if (base == null) {
                    val lh = local.hash(rel) ?: throw Exception("本地文件不可读")
                    uploadNew(ctx, rel, le, lh)
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
        val lh = local.hash(rel) ?: throw Exception("本地文件不可读")
        val b = base
        if (b == null) {
            // 首次遇到：两端一致直接采纳，否则按冲突处理
            if (lh == re.sha256) {
                ctx.record(rel, lh, le.size)
                ctx.unchanged++
                log("已一致 $rel")
            } else {
                conflictChoice(ctx, rel, le, lh, re)
            }
            return
        }
        val localChanged = lh != b.sha256
        val serverChanged = re.sha256 != b.sha256
        when {
            !localChanged && !serverChanged -> ctx.unchanged++
            localChanged && !serverChanged -> {
                val data = local.read(rel) ?: throw Exception("本地文件不可读")
                when (server.put(rel, data, true, b.sha256)) {
                    PutStatus.CREATED -> {
                        ctx.record(rel, lh, le.size)
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
            else -> {
                if (lh == re.sha256) {
                    ctx.record(rel, lh, le.size)
                    ctx.unchanged++
                    log("已一致 $rel")
                } else {
                    conflictChoice(ctx, rel, le, lh, re)
                }
            }
        }
    }

    private fun uploadNew(ctx: Ctx, rel: String, le: LocalEntry, lh: String) {
        val data = local.read(rel) ?: throw Exception("本地文件不可读")
        when (server.put(rel, data, false, null)) {
            PutStatus.CREATED -> {
                ctx.record(rel, lh, le.size)
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
                val data = local.read(rel) ?: throw Exception("本地文件不可读")
                if (server.put(rel, data, true, null) != PutStatus.CREATED) throw Exception("上传被拒绝")
                ctx.record(rel, lh, le.size)
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
