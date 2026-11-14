package com.filesync.app.api

interface ServerApi {
    fun ping(): Boolean

    /** 探测连通性，返回 (可达, 失败原因)。 */
    fun probe(): Pair<Boolean, String>

    fun ls(prefix: String = ""): List<RemoteFile>

    /** 返回 (内容, 服务器校验和)；远端不存在时返回 null。 */
    fun get(path: String): Pair<ByteArray, String>?

    fun put(path: String, data: ByteArray, overwrite: Boolean, ifMatch: String?): PutStatus

    /** 删除云端文件（仅云端，不影响本地）。返回 true=已删除或云端本就不存在。 */
    fun delete(path: String): Boolean
}
