package com.filesync.app

import com.filesync.app.api.HttpServerApi
import com.filesync.app.api.PutStatus
import com.filesync.app.util.sha256Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 对接真实 Go 服务端的集成探针。运行前设置环境变量 FILESYNC_TEST_SERVER（如 http://127.0.0.1:18099）。 */
class ServerIntegrationTest {

    private val base: String? = System.getenv("FILESYNC_TEST_SERVER")

    @Test
    fun `与 Go 服务端集成`() {
        assumeTrue(base != null && base.isNotBlank())
        val api = HttpServerApi(base!!)
        assertTrue("ping 应成功", api.ping())

        val uniq = "android-it-" + System.currentTimeMillis()
        val md = "# 测试标题\n\n内容 **加粗** 与 `代码`。\n".toByteArray(Charsets.UTF_8)

        assertEquals(PutStatus.CREATED, api.put("$uniq/笔记 A.md", md, false, null))
        val (data, sha) = api.get("$uniq/笔记 A.md")!!
        assertArrayEquals(md, data)
        assertEquals(sha256Hex(md), sha)

        assertEquals(PutStatus.CONFLICT, api.put("$uniq/笔记 A.md", md, false, null))
        assertEquals(PutStatus.CREATED, api.put("$uniq/笔记 A.md", "v2".toByteArray(), true, sha))
        assertEquals(PutStatus.CONFLICT, api.put("$uniq/笔记 A.md", "v3".toByteArray(), true, sha256Hex(md)))

        for (i in 1..5) {
            assertEquals(PutStatus.CREATED, api.put("$uniq/bulk/f$i.bin", ByteArray(1024 * i), false, null))
        }
        assertEquals(5, api.ls("$uniq/bulk").size)

        assertEquals(PutStatus.CREATED, api.put("$uniq/empty.bin", ByteArray(0), false, null))
        val (e, es) = api.get("$uniq/empty.bin")!!
        assertEquals(0, e.size)
        assertEquals(sha256Hex(ByteArray(0)), es)
    }
}
