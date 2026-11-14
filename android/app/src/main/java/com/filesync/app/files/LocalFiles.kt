package com.filesync.app.files

/**
 * path 为相对路径；handle 是扫描时绑定的文件标识（SAF 下为 document Uri，
 * 测试实现为 null）。ambiguous 表示同一路径在目录中存在多个同名条目，
 * 此时 handle 为 null，同步引擎必须跳过该文件，不允许任选其一。
 */
data class LocalEntry(
    val path: String,
    val size: Long,
    val handle: Any?,
    val ambiguous: Boolean = false,
)

data class DirEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val ambiguous: Boolean = false,
)

interface LocalFiles {
    /** 枚举本地文件并绑定 handle；同名多文档时该条目 ambiguous=true。 */
    fun list(): List<LocalEntry>

    /** 基于扫描时绑定的 handle 计算哈希，避免按名字二次定位。 */
    fun hash(e: LocalEntry): String?

    fun read(e: LocalEntry): ByteArray?

    /** 原子写入：临时文件（时间戳命名）+ 删旧目标 + 改名。目标名歧义时拒绝写入。 */
    fun write(rel: String, data: ByteArray): Boolean
}
