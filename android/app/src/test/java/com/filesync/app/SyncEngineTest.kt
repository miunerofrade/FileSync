package com.filesync.app

import com.filesync.app.api.PutStatus
import com.filesync.app.api.RemoteFile
import com.filesync.app.api.ServerApi
import com.filesync.app.files.LocalEntry
import com.filesync.app.files.LocalFiles
import com.filesync.app.sync.StateEntry
import com.filesync.app.sync.StateStore
import com.filesync.app.sync.SyncEngine
import com.filesync.app.util.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private fun b(s: String) = s.toByteArray(Charsets.UTF_8)

class FakeLocal(initial: Map<String, String> = emptyMap()) : LocalFiles {
    val files = initial.mapValues { b(it.value) }.toMutableMap()

    override fun list() = files.map { LocalEntry(it.key, it.value.size.toLong()) }

    override fun hash(rel: String) = files[rel]?.let { sha256Hex(it) }

    override fun read(rel: String) = files[rel]

    override fun write(rel: String, data: ByteArray): Boolean {
        files[rel] = data
        return true
    }
}

class FakeServer : ServerApi {
    val files = LinkedHashMap<String, ByteArray>()
    var onPut: (() -> Unit)? = null

    override fun ping() = true

    override fun ls(prefix: String) = files.entries
        .filter { it.key.startsWith(prefix) }
        .map { RemoteFile(it.key, it.value.size.toLong(), sha256Hex(it.value), "") }

    override fun get(path: String): Pair<ByteArray, String>? =
        files[path]?.let { it to sha256Hex(it) }

    override fun put(path: String, data: ByteArray, overwrite: Boolean, ifMatch: String?): PutStatus {
        onPut?.invoke()
        val ex = files[path]
        if (ex != null && !overwrite) return PutStatus.CONFLICT
        if (ifMatch != null) {
            if (ex == null || sha256Hex(ex) != ifMatch) return PutStatus.CONFLICT
        }
        files[path] = data
        return PutStatus.CREATED
    }
}

class MemState(initial: Map<String, StateEntry> = emptyMap()) : StateStore {
    val files = initial.toMutableMap()
    var saves = 0

    override fun load() = files.toMap()

    override fun save(files: Map<String, StateEntry>) {
        this.files.clear()
        this.files.putAll(files)
        saves++
    }
}

class SyncEngineTest {

    private suspend fun sync(
        local: FakeLocal,
        server: FakeServer,
        state: MemState,
        filter: ((String) -> Boolean)? = null,
        ask: (String) -> String = { "skip" },
    ): SyncEngine.Stats {
        val engine = SyncEngine(local, server, state, { }, { info -> ask(info.rel) })
        return engine.sync(filter)
    }

    @Test
    fun `首次同步上传全部本地文件`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1", "d/b.md" to "n1"))
        val server = FakeServer()
        val state = MemState()
        val st = sync(local, server, state)
        assertEquals(2, st.uploaded)
        assertEquals("v1", String(server.files["a.md"]!!))
        assertEquals("n1", String(server.files["d/b.md"]!!))
        assertEquals(2, state.files.size)
    }

    @Test
    fun `重复同步无动作`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        val st = sync(local, server, state)
        assertEquals(0, st.uploaded)
        assertEquals(0, st.downloaded)
        assertEquals(1, st.unchanged)
        assertEquals(0, state.saves)
    }

    @Test
    fun `本地修改自动上传`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files["a.md"] = b("v2")
        val st = sync(local, server, state)
        assertEquals(1, st.uploaded)
        assertEquals(0, st.skipped)
        assertEquals("v2", String(server.files["a.md"]!!))
    }

    @Test
    fun `服务器变化自动下载`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        server.files["a.md"] = b("from-other")
        val st = sync(local, server, state)
        assertEquals(1, st.downloaded)
        assertEquals("from-other", String(local.files["a.md"]!!))
    }

    @Test
    fun `两端改成相同内容只记录基线`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files["a.md"] = b("same")
        server.files["a.md"] = b("same")
        val before = server.files["a.md"]!!.size
        val st = sync(local, server, state)
        assertEquals(1, st.unchanged)
        assertEquals(0, st.uploaded)
        assertEquals(0, st.downloaded)
        assertEquals(before, server.files["a.md"]!!.size)
    }

    @Test
    fun `首次遇到两端不同内容按冲突处理`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "local-v"))
        val server = FakeServer()
        server.files["a.md"] = b("server-v")
        val state = MemState()

        val skipped = sync(local, server, state, ask = { "skip" })
        assertEquals(1, skipped.skipped)
        assertEquals("server-v", String(server.files["a.md"]!!))
        assertEquals("local-v", String(local.files["a.md"]!!))

        val keepLocal = sync(local, server, state, ask = { "local" })
        assertEquals(1, keepLocal.resolved)
        assertEquals("local-v", String(server.files["a.md"]!!))

        val keepRemote = sync(local, server, state, ask = { "remote" })
        assertEquals(1, keepRemote.resolved)
        assertEquals("server-v", String(local.files["a.md"]!!))
    }

    @Test
    fun `两端分别修改冲突保留本地`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files["a.md"] = b("L2")
        server.files["a.md"] = b("R2")
        val st = sync(local, server, state, ask = { "local" })
        assertEquals(1, st.resolved)
        assertEquals("L2", String(server.files["a.md"]!!))
    }

    @Test
    fun `两端分别修改冲突保留远端`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files["a.md"] = b("L2")
        server.files["a.md"] = b("R2")
        val st = sync(local, server, state, ask = { "remote" })
        assertEquals(1, st.resolved)
        assertEquals("R2", String(local.files["a.md"]!!))
    }

    @Test
    fun `两端分别修改冲突跳过不覆盖`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files["a.md"] = b("L2")
        server.files["a.md"] = b("R2")
        val st = sync(local, server, state, ask = { "skip" })
        assertEquals(1, st.skipped)
        assertEquals("R2", String(server.files["a.md"]!!))
        assertEquals("L2", String(local.files["a.md"]!!))
    }

    @Test
    fun `上传瞬间服务器被并发修改视为冲突不覆盖`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files["a.md"] = b("v2")
        server.onPut = { server.files["a.md"] = b("sneaky") }
        val st = sync(local, server, state)
        assertEquals(1, st.skipped)
        assertEquals(0, st.uploaded)
        assertEquals("sneaky", String(server.files["a.md"]!!))
    }

    @Test
    fun `本地删除不传播`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files.remove("a.md")
        val st = sync(local, server, state)
        assertEquals(1, st.warnings)
        assertEquals(0, st.cleaned)
        assertTrue("服务器文件保留", server.files.containsKey("a.md"))
    }

    @Test
    fun `服务器删除不传播`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        server.files.remove("a.md")
        val st = sync(local, server, state)
        assertEquals(1, st.warnings)
        assertTrue("本地文件保留", local.files.containsKey("a.md"))
    }

    @Test
    fun `两端均删除清理基线`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1"))
        val server = FakeServer()
        val state = MemState()
        sync(local, server, state)
        local.files.remove("a.md")
        server.files.remove("a.md")
        val st = sync(local, server, state)
        assertEquals(1, st.cleaned)
        assertEquals(0, state.files.size)
    }

    @Test
    fun `过滤条件限制处理范围`() = runBlocking {
        val local = FakeLocal(mapOf("a.md" to "v1", "b.md" to "v2"))
        val server = FakeServer()
        val state = MemState()
        val st = sync(local, server, state, filter = { it == "a.md" })
        assertEquals(1, st.uploaded)
        assertTrue(server.files.containsKey("a.md"))
        assertEquals(false, server.files.containsKey("b.md"))
    }
}
