package com.zaralynchisel.editioncore

import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.withBatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Handles batch operations on chunks and blocks.
 * Operations are processed in parallel batches with progress reporting.
 */
class BlockBatchProcessor(
    private val worldPath: String,
    private val batchSize: Int = 16
) {

    /**
     * Result of a batch operation.
     */
    data class BatchResult(
        val successCount: Int,
        val failureCount: Int,
        val totalChunks: Int,
        val errors: List<String> = emptyList()
    )

    /**
     * Progress update during batch processing.
     */
    data class BatchProgress(
        val processed: Int,
        val total: Int,
        val currentOperation: String,
        val isComplete: Boolean = false
    )

    /**
     * Delete chunks in the given selection area.
     * Returns a flow of progress updates.
     */
    fun deleteChunks(
        dimension: DimensionType,
        selection: SelectionArea
    ): Flow<BatchProgress> = flow {
        val chunks = resolveChunks(dimension, selection)
        val total = chunks.size
        emit(BatchProgress(0, total, "Preparing deletion..."))

        val regionMap = groupChunksByRegion(chunks)
        var processed = 0
        val errors = mutableListOf<String>()

        for ((regionPos, localChunks) in regionMap) {
            emit(BatchProgress(processed, total, "Processing region ${regionPos.fileName()}..."))

            val regionFile = resolveRegionFile(dimension, regionPos)
            if (regionFile == null || !regionFile.exists()) {
                errors.add("Region file not found: ${regionPos.fileName()}")
                processed += localChunks.size
                continue
            }

            val writer = AnvilWriter(regionFile)
            if (!writer.open()) {
                errors.add("Failed to open: ${regionPos.fileName()}")
                processed += localChunks.size
                continue
            }

            for ((lx, lz) in localChunks) {
                try {
                    writer.deleteChunk(lx, lz)
                } catch (e: Exception) {
                    errors.add("Failed to delete chunk ($lx, $lz) in ${regionPos.fileName()}: ${e.message}")
                }
                processed++
                if (processed % batchSize == 0) {
                    emit(BatchProgress(processed, total, "Deleting chunks..."))
                }
            }
            writer.close()
        }

        emit(BatchProgress(total, total, "Deletion complete", isComplete = true))
    }

    /**
     * Void area: replace all non-air blocks with air in the selection.
     */
    fun voidArea(
        dimension: DimensionType,
        selection: SelectionArea
    ): Flow<BatchProgress> = flow {
        val chunks = resolveChunks(dimension, selection)
        val total = chunks.size
        emit(BatchProgress(0, total, "Preparing void operation..."))

        var processed = 0
        val errors = mutableListOf<String>()

        for (chunk in chunks) {
            emit(BatchProgress(processed, total, "Voiding chunk ($chunk)..."))
            // TODO: Read chunk NBT, replace all non-air blocks, write back
            processed++
        }

        emit(BatchProgress(total, total, "Void operation complete", isComplete = true))
    }

    /**
     * Copy chunks from selection to clipboard.
     */
    suspend fun copyChunks(
        dimension: DimensionType,
        selection: SelectionArea
    ): ChunkClipboard? {
        return withBatch {
            Logger.d("Copying chunks in $dimension: $selection")
            // TODO: Read chunk data and store in clipboard
            null
        }
    }

    /**
     * Paste chunks from clipboard at the given origin.
     */
    suspend fun pasteChunks(
        dimension: DimensionType,
        originChunkX: Int,
        originChunkZ: Int,
        clipboard: ChunkClipboard
    ): Flow<BatchProgress> = flow {
        emit(BatchProgress(0, clipboard.chunks.size, "Preparing paste..."))
        // TODO: Write clipboard chunks to new positions
        emit(BatchProgress(clipboard.chunks.size, clipboard.chunks.size, "Paste complete", isComplete = true))
    }

    // ── Private helpers ────────────────────────────────────────────────

    private fun resolveChunks(dimension: DimensionType, selection: SelectionArea): List<ChunkPos> {
        return when (selection) {
            is SelectionArea.Rectangle -> {
                (selection.minChunkX..selection.maxChunkX).flatMap { x ->
                    (selection.minChunkZ..selection.maxChunkZ).map { z ->
                        ChunkPos(x, z)
                    }
                }
            }
            is SelectionArea.Circle -> {
                val chunks = mutableListOf<ChunkPos>()
                val radiusSq = selection.radiusChunks * selection.radiusChunks
                for (dx in -selection.radiusChunks..selection.radiusChunks) {
                    for (dz in -selection.radiusChunks..selection.radiusChunks) {
                        if (dx * dx + dz * dz <= radiusSq) {
                            chunks.add(ChunkPos(selection.centerChunkX + dx, selection.centerChunkZ + dz))
                        }
                    }
                }
                chunks
            }
        }
    }

    private fun groupChunksByRegion(chunks: List<ChunkPos>): Map<RegionPos, List<Pair<Int, Int>>> {
        val map = mutableMapOf<RegionPos, MutableList<Pair<Int, Int>>>()
        for (chunk in chunks) {
            val region = chunk.toRegionPos()
            val localX = chunk.x and 31
            val localZ = chunk.z and 31
            map.getOrPut(region) { mutableListOf() }.add(Pair(localX, localZ))
        }
        return map
    }

    private fun resolveRegionFile(dimension: DimensionType, regionPos: RegionPos): File? {
        val regionDir = File(worldPath, dimension.folderName)
        return File(regionDir, regionPos.fileName())
    }
}

/**
 * Clipboard data for cross-world chunk copy/paste.
 */
data class ChunkClipboard(
    val chunks: List<Pair<ChunkPos, ByteArray>>,
    val originChunkX: Int,
    val originChunkZ: Int,
    val dimension: DimensionType
)