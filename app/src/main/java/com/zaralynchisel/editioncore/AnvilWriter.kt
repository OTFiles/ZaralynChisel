package com.zaralynchisel.editioncore

import com.zaralynchisel.utils.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Writes Minecraft Anvil (.mca) region files.
 * Handles chunk data writing with proper sector alignment and reuse of
 * sectors freed by deleted chunks.
 */
class AnvilWriter(private val regionFile: File) {

    private var raf: RandomAccessFile? = null

    fun open(): Boolean {
        return try {
            raf = RandomAccessFile(regionFile, "rw")
            Logger.d("Opened region for writing: ${regionFile.name}")
            true
        } catch (e: Exception) {
            Logger.e("Failed to open region file for writing", e)
            false
        }
    }

    /** Sector size in bytes (region files are divided into 4 KiB sectors). */
    private companion object { const val SECTOR_BYTES = 4096 }

    /**
     * Write a chunk to the region file.
     *
     * The on-disk layout for each chunk is:
     *   [4-byte big-endian length] [1-byte compressionType] [nbtData ...] [padding to sector]
     * where length = 1 + nbtData.size.
     *
     * @param nbtData  already-compressed NBT data, WITHOUT the compression-type
     *                 byte (i.e. the bytes that [AnvilReader.readChunkData] returns).
     * @param compressionType  1 = GZip, 2 = Zlib (default), 3 = uncompressed.
     */
    fun writeChunk(localX: Int, localZ: Int, nbtData: ByteArray, compressionType: Int = 2): Boolean {
        val file = raf ?: return false
        return try {
            val totalLength = 1 + nbtData.size   // compression-type byte + NBT data
            val sectorsNeeded = (4 + totalLength + SECTOR_BYTES - 1) / SECTOR_BYTES  // 4 = length field

            val sectorOffset = findFreeSector(sectorsNeeded)
            val byteOffset = sectorOffset * SECTOR_BYTES.toLong()

            file.seek(byteOffset)

            // Length (big-endian)
            val lenBuf = ByteBuffer.allocate(4).putInt(totalLength).array()
            file.write(lenBuf)

            // Compression type byte
            file.write(compressionType)

            // Compressed NBT
            file.write(nbtData)

            // Pad to sector boundary
            val written = 4 + totalLength
            val padding = sectorsNeeded * SECTOR_BYTES - written
            if (padding > 0) file.write(ByteArray(padding))

            // Update location table (first 4 KiB)
            writeLocationEntry(localX, localZ, sectorOffset, sectorsNeeded)

            Logger.d("Written chunk ($localX, $localZ) at sector $sectorOffset ($sectorsNeeded sectors)")
            true
        } catch (e: Exception) {
            Logger.e("Failed to write chunk at ($localX, $localZ)", e)
            false
        }
    }

    // ── Location table helpers ────────────────────────────────────────

    /**
     * Update the region header entry for the chunk at (localX, localZ).
     */
    private fun writeLocationEntry(localX: Int, localZ: Int, sectorOffset: Int, sectorCount: Int) {
        val file = raf ?: return
        val locOff = 4L * (localX + localZ * 32)
        file.seek(locOff)
        file.write(
            byteArrayOf(
                ((sectorOffset shr 16) and 0xFF).toByte(),
                ((sectorOffset shr 8) and 0xFF).toByte(),
                (sectorOffset and 0xFF).toByte(),
                (sectorCount and 0xFF).toByte()
            )
        )
        // Timestamp
        val tsOff = locOff + SECTOR_BYTES
        file.seek(tsOff)
        file.write(
            ByteBuffer.allocate(4).putInt((System.currentTimeMillis() / 1000).toInt()).array()
        )
    }

    /**
     * Read the location table (first 4 KiB, 1024 × 4-byte entries) and return a set
     * of sector ranges that are currently in use.
     */
    private fun readUsedSectorRanges(): List<IntRange> {
        val file = raf ?: return emptyList()
        return try {
            file.seek(0)
            val locRaw = ByteArray(SECTOR_BYTES)
            file.readFully(locRaw)
            val buf = ByteBuffer.wrap(locRaw)
            val ranges = mutableListOf<IntRange>()
            for (i in 0 until 1024) {
                val entry = buf.getInt(i * 4)
                if (entry == 0) continue    // deleted / never written
                val offset = entry ushr 8
                val count = entry and 0xFF
                if (offset > 0 && count > 0) ranges.add(offset until offset + count)
            }
            ranges.sortBy { it.first }
            ranges
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Find a free area of at least [sectorsNeeded] sectors for a new chunk.
     * Tries to reuse a gap left by previously-deleted chunks, then falls back to
     * appending at the end of the file.
     */
    private fun findFreeSector(sectorsNeeded: Int): Int {
        val file = raf ?: return 2
        val used = readUsedSectorRanges()
        // Try each gap between used ranges (after header sectors 0–1)
        var prevEnd = 2
        for (r in used) {
            if (r.first > prevEnd + sectorsNeeded - 1) return prevEnd  // gap big enough
            prevEnd = maxOf(prevEnd, r.last + 1)
        }
        // No gap → append at end
        val endSector = (file.length() / SECTOR_BYTES).toInt().coerceAtLeast(prevEnd)
        return endSector.coerceAtLeast(2)
    }

    /**
     * Mark a chunk as empty (zero out its location-table entry – 3 zero bytes for
     * sector-offset, 1 zero byte for sector-count). Does not reclaim the old sectors;
     * [findFreeSector] will use the resulting gap on a future write.
     */
    fun deleteChunk(localX: Int, localZ: Int): Boolean {
        val file = raf ?: return false
        return try {
            val locationOffset = 4L * (localX + localZ * 32)
            file.seek(locationOffset)
            file.write(ByteArray(4))
            Logger.d("Deleted chunk ($localX, $localZ)")
            true
        } catch (e: Exception) {
            Logger.e("Failed to delete chunk at ($localX, $localZ)", e)
            false
        }
    }

    fun close() {
        try { raf?.close() } catch (_: Exception) { }
        raf = null
    }
}
