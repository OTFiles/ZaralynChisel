package com.zaralynchisel.fileaccess

import com.zaralynchisel.editioncore.ChunkInfo
import com.zaralynchisel.editioncore.WorldData

/**
 * In-memory cache for loaded world data and chunk lists.
 * Avoids re-scanning when navigating between screens.
 */
object WorldCache {
    private var cachedWorld: WorldData? = null
    private var cachedChunks: List<ChunkInfo>? = null
    private var cachedWorldPath: String? = null

    fun get(worldPath: String): Pair<WorldData, List<ChunkInfo>>? {
        if (cachedWorldPath == worldPath && cachedWorld != null && cachedChunks != null) {
            return cachedWorld to cachedChunks!!
        }
        return null
    }

    fun put(worldPath: String, world: WorldData, chunks: List<ChunkInfo>) {
        cachedWorldPath = worldPath
        cachedWorld = world
        cachedChunks = chunks
    }

    fun clear() {
        cachedWorldPath = null
        cachedWorld = null
        cachedChunks = null
    }
}