package com.zaralynchisel.editioncore

import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.withBatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayInputStream

/**
 * Handles batch operations on chunks and blocks.
 * Uses NbtReader for all NBT parsing.
 */
class BlockBatchProcessor(
    private val worldPath: String,
    private val batchSize: Int = 16
) {

    data class BatchResult(
        val successCount: Int,
        val failureCount: Int,
        val totalChunks: Int,
        val errors: List<String> = emptyList()
    )

    data class BatchProgress(
        val processed: Int,
        val total: Int,
        val currentOperation: String,
        val isComplete: Boolean = false
    )

    fun deleteChunks(
        dimension: DimensionType,
        selection: SelectionArea
    ): Flow<BatchProgress> = flow {
        val chunks = resolveChunks(dimension, selection)
        val total = chunks.size
        emit(BatchProgress(0, total, "准备删除..."))

        val regionMap = groupChunksByRegion(chunks)
        var processed = 0
        val errors = mutableListOf<String>()

        for ((regionPos, localChunks) in regionMap) {
            emit(BatchProgress(processed, total, "处理区域 ${regionPos.fileName()}..."))

            val regionFile = resolveRegionFile(dimension, regionPos)
            if (regionFile == null || !regionFile.exists()) {
                errors.add("区域文件不存在: ${regionPos.fileName()}")
                processed += localChunks.size
                continue
            }

            val writer = AnvilWriter(regionFile)
            if (!writer.open()) {
                errors.add("无法打开: ${regionPos.fileName()}")
                processed += localChunks.size
                continue
            }

            for ((lx, lz) in localChunks) {
                try {
                    writer.deleteChunk(lx, lz)
                    Logger.d("Deleted chunk ($lx, $lz) in ${regionPos.fileName()}")
                } catch (e: Exception) {
                    errors.add("删除区块失败 ($lx, $lz): ${e.message}")
                }
                processed++
                if (processed % batchSize == 0) {
                    emit(BatchProgress(processed, total, "删除区块中..."))
                }
            }
            writer.close()
        }

        emit(BatchProgress(total, total, "删除完成 ($total 个区块)", isComplete = true))
    }

    /**
     * Void area: remove chunks from the selection (equivalent to deleting them —
     * the map will show those chunks as transparent, which is the expected visual).
     */
    fun voidArea(
        dimension: DimensionType,
        selection: SelectionArea
    ): Flow<BatchProgress> = deleteChunks(dimension, selection)

    suspend fun copyChunks(
        dimension: DimensionType,
        selection: SelectionArea
    ): ChunkClipboard? {
        return withBatch {
            val chunks = resolveChunks(dimension, selection)
            if (chunks.isEmpty()) return@withBatch null

            val regionMap = groupChunksByRegion(chunks)
            val clipboardData = mutableListOf<Pair<ChunkPos, ByteArray>>()

            for ((regionPos, localChunks) in regionMap) {
                val regionFile = resolveRegionFile(dimension, regionPos) ?: continue
                val reader = AnvilReader(regionFile)
                if (!reader.open()) continue

                for ((lx, lz) in localChunks) {
                    val data = reader.readChunkData(lx, lz)
                    if (data != null) {
                        clipboardData.add(
                            ChunkPos((regionPos.x shl 5) + lx, (regionPos.z shl 5) + lz) to data
                        )
                    }
                }
                reader.close()
            }

            if (clipboardData.isEmpty()) return@withBatch null

            val minX = clipboardData.minOf { it.first.x }
            val minZ = clipboardData.minOf { it.first.z }

            ChunkClipboard(
                chunks = clipboardData,
                originChunkX = minX,
                originChunkZ = minZ,
                dimension = dimension
            )
        }
    }

    suspend fun pasteChunks(
        dimension: DimensionType,
        originChunkX: Int,
        originChunkZ: Int,
        clipboard: ChunkClipboard
    ): Flow<BatchProgress> = flow {
        val total = clipboard.chunks.size
        emit(BatchProgress(0, total, "准备粘贴..."))

        val offsetX = originChunkX - clipboard.originChunkX
        val offsetZ = originChunkZ - clipboard.originChunkZ

        val shiftedChunks = clipboard.chunks.map { (pos, data) ->
            ChunkPos(pos.x + offsetX, pos.z + offsetZ) to data
        }

        val regionMap = mutableMapOf<RegionPos, MutableList<Triple<Int, Int, ByteArray>>>()
        for ((pos, data) in shiftedChunks) {
            val region = pos.toRegionPos()
            val lx = pos.x and 31
            val lz = pos.z and 31
            regionMap.getOrPut(region) { mutableListOf() }.add(Triple(lx, lz, data))
        }

        var processed = 0
        val errors = mutableListOf<String>()

        for ((regionPos, entries) in regionMap) {
            emit(BatchProgress(processed, total, "写入区域 ${regionPos.fileName()}..."))

            val regionFile = resolveRegionFile(dimension, regionPos)
            if (regionFile == null) {
                errors.add("无法解析区域文件: ${regionPos.fileName()}")
                processed += entries.size
                continue
            }

            val writer = AnvilWriter(regionFile)
            if (!writer.open()) {
                errors.add("无法打开: ${regionPos.fileName()}")
                processed += entries.size
                continue
            }

            for ((lx, lz, data) in entries) {
                try {
                    writer.writeChunk(lx, lz, data, 2) // zlib-compressed from readChunkData
                } catch (e: Exception) {
                    errors.add("粘贴失败 ($lx, $lz): ${e.message}")
                }
                processed++
            }
            writer.close()
        }

        emit(BatchProgress(total, total, "粘贴完成", isComplete = true))
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun resolveChunks(dimension: DimensionType, selection: SelectionArea): List<ChunkPos> {
        return when (selection) {
            is SelectionArea.Rectangle -> {
                (selection.minChunkX..selection.maxChunkX).flatMap { x ->
                    (selection.minChunkZ..selection.maxChunkZ).map { z -> ChunkPos(x, z) }
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
            map.getOrPut(region) { mutableListOf() }.add(Pair(chunk.x and 31, chunk.z and 31))
        }
        return map
    }

    private fun resolveRegionFile(dimension: DimensionType, regionPos: RegionPos): File? {
        val regionDir = File(worldPath, dimension.folderName)
        return File(regionDir, regionPos.fileName())
    }

    companion object {
    }
}

data class ChunkClipboard(
    val chunks: List<Pair<ChunkPos, ByteArray>>,
    val originChunkX: Int,
    val originChunkZ: Int,
    val dimension: DimensionType
)