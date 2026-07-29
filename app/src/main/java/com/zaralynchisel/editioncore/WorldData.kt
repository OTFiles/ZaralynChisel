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
        fun fromFolder(path: String): DimensionType? {
            // Match the most specific dimension first. The previous implementation checked
            // OVERWORLD ("region") before NETHER/END, so a path like ".../DIM-1/region"
            // matched OVERWORLD's "/region" substring and every nether/end chunk was
            // misclassified as overworld (wrong minY, wrong palette assumptions).
            val normalized = path.replace('\\', '/')
            return when {
                normalized.contains("/DIM-1/") -> NETHER
                normalized.contains("/DIM1/") -> END
                normalized.contains("/region") -> OVERWORLD
                else -> null
            }
        }
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
    /** Size of the chunk on disk in 4 KiB sectors (from the region header). Larger =
     *  more generated content; "structure_starts" stubs are tiny (1 sector). Used to
     *  locate generated terrain without parsing every chunk. */
    val sectorCount: Int = 0,
    val blockCount: Int = 0,
    val averageHeight: Int = 0,
    /** 16×16 MapColor IDs for each column (index = z*16 + x). null if not loaded. */
    val surfaceColors: IntArray? = null
) {
    val hasSurfaceData: Boolean get() = surfaceColors != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkInfo) return false
        return x == other.x && z == other.z && dimension == other.dimension
    }

    override fun hashCode(): Int = (x * 31 + z) * 31 + dimension.hashCode()
}

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