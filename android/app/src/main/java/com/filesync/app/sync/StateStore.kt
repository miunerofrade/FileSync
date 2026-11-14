package com.filesync.app.sync

data class StateEntry(val sha256: String, val size: Long, val syncedAt: String = "")

interface StateStore {
    fun load(): Map<String, StateEntry>

    fun save(files: Map<String, StateEntry>)
}
