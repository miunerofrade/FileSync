package com.filesync.app.files

data class LocalEntry(val path: String, val size: Long)

data class DirEntry(val name: String, val isDir: Boolean, val size: Long)

interface LocalFiles {
    fun list(): List<LocalEntry>

    fun hash(rel: String): String?

    fun read(rel: String): ByteArray?

    /** 原子写入：先写临时文件再改名，中断不损坏已有内容。 */
    fun write(rel: String, data: ByteArray): Boolean
}
