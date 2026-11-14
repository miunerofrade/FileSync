package com.filesync.app.api

data class RemoteFile(
    val path: String,
    val size: Long,
    val sha256: String,
    val modTime: String,
)

enum class PutStatus { CREATED, CONFLICT, ERROR }

class ServerException(message: String, val code: Int = 0) : Exception(message)
