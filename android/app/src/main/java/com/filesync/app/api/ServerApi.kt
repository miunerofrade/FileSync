package com.filesync.app.api

interface ServerApi {
    fun ping(): Boolean

    fun ls(prefix: String = ""): List<RemoteFile>

    /** 返回 (内容, 服务器校验和)；远端不存在时返回 null。 */
    fun get(path: String): Pair<ByteArray, String>?

    fun put(path: String, data: ByteArray, overwrite: Boolean, ifMatch: String?): PutStatus
}
