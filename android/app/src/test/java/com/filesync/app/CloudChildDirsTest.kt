package com.filesync.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudChildDirsTest {

    private val paths = listOf(
        "a.md",                 // 根目录散文件
        "b.txt",
        "sub1/y.txt",
        "sub1/deep/z.md",       // 嵌套子目录
        "sub2/x.txt",
        "sub10/w.txt",          // 与 sub1 仅前缀重叠
    )

    @Test
    fun `根目录只显示子目录不显示散文件`() {
        assertEquals(listOf("sub1", "sub10", "sub2"), cloudChildDirs(paths, ""))
    }

    @Test
    fun `子目录内只显示自己的子目录`() {
        assertEquals(listOf("deep"), cloudChildDirs(paths, "sub1"))
    }

    @Test
    fun `兄弟目录与前缀重叠目录不混入`() {
        // 在 sub1 里浏览时，sub2 / sub10 是兄弟，不能显示成 sub1 的子目录
        assertEquals(emptyList<String>(), cloudChildDirs(paths.filterNot { it.startsWith("sub1/") }, "sub1"))
    }

    @Test
    fun `嵌套层内无更深子目录时为空`() {
        // sub1/deep 下只有文件 z.md，没有子目录
        assertEquals(emptyList<String>(), cloudChildDirs(paths, "sub1/deep"))
    }

    @Test
    fun `深层目录与浅层互不干扰`() {
        assertEquals(
            listOf("sub1", "sub10", "sub2"),
            cloudChildDirs(listOf("sub1/deep/z.md", "sub2/x.txt", "sub10/w.txt"), ""),
        )
    }
}
