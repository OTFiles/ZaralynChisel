package com.zaralynchisel.editioncore

import kotlinx.serialization.Serializable

/**
 * Represents a Minecraft Java Edition world.
 */
data class WorldData(
    val worldName: String,
    val rootPath: String,
    val dimensionPaths: Map<DimensionType, String> = emptyMap(),
    val gameVersion: String = "unknown",
    val dataVersion: Int = 0,
    val seed: Long = 0,
    val spawnX: Int = 0,
    val spawnZ: Int = 0,
    val lastPlayed: Long = 0L,
    val levelData: Map<String, Any?> = emptyMap()
)

/**
 * Minecraft dimension types.
 */
@Serializable
enum class DimensionType(val folderName: String) {
    OVERWORLD("region"),
    NETHER("DIM-1/region"),
    END("DIM1/region");

    companion object {
        fun fromFolder(folder: String): DimensionType? =
            entries.firstOrNull { folder.contains(it.folderName.removeSuffix("/region")) }
    }
}

/**
 * Represents a single chunk's metadata.
 */
data class ChunkInfo(
    val x: Int,
    val z: Int,
    val dimension: DimensionType,
    val timestamp: Long = 0L,
    val isEmpty: Boolean = false,
    val isCorrupted: Boolean = false,
    val blockCount: Int = 0
)

/**
 * Represents a block position within a world.
 */
data class BlockPos(
    val x: Int,
    val y: Int,
    val z: Int
) {
    fun toChunkPos(): ChunkPos = ChunkPos(x shr 4, z shr 4)

    companion object {
        fun fromChunk(chunkX: Int, chunkZ: Int, blockY: Int = 64): BlockPos =
            BlockPos(chunkX shl 4, blockY, chunkZ shl 4)
    }
}

/**
 * Represents a chunk coordinate pair.
 */
data class ChunkPos(
    val x: Int,
    val z: Int
) {
    fun toRegionPos(): RegionPos = RegionPos(x shr 5, z shr 5)

    companion object {
        fun fromRegion(regionX: Int, regionZ: Int, localX: Int, localZ: Int): ChunkPos =
            ChunkPos((regionX shl 5) + localX, (regionZ shl 5) + localZ)
    }
}

/**
 * Represents a region file coordinate (r.x.z.mca).
 */
data class RegionPos(
    val x: Int,
    val z: Int
) {
    fun fileName(): String = "r.$x.$z.mca"
}

/**
 * Selection area types for batch operations.
 */
sealed class SelectionArea {
    data class Rectangle(
        val minChunkX: Int,
        val minChunkZ: Int,
        val maxChunkX: Int,
        val maxChunkZ: Int
    ) : SelectionArea()

    data class Circle(
        val centerChunkX: Int,
        val centerChunkZ: Int,
        val radiusChunks: Int
    ) : SelectionArea()
}

/**
 * Types of batch operations.
 */
enum class BatchOperationType {
    DELETE_CHUNKS,
    VOID_AREA,
    COPY,
    CUT,
    PASTE
}

/**
 * Represents a single history entry for undo/redo.
 */
data class HistoryEntry(
    val id: Long,
    val operationType: BatchOperationType,
    val description: String,
    val timestamp: Long,
    val affectedChunks: List<ChunkPos>,
    val undoData: ByteArray? = null  // Serialized NBT backup
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistoryEntry) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}