package com.zaralynchisel.editioncore

import com.zaralynchisel.utils.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Writes Minecraft Anvil (.mca) region files.
 * Handles chunk data writing with proper sector alignment.
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

    /**
     * Write compressed chunk data to the region file.
     * @param localX Chunk X coordinate within region (0-31)
     * @param localZ Chunk Z coordinate within region (0-31)
     * @param compressedData The compressed NBT data (including compression type byte)
     */
    fun writeChunk(localX: Int, localZ: Int, compressedData: ByteArray): Boolean {
        val file = raf ?: return false
        return try {
            val totalLength = 1 + compressedData.size  // 1 byte compression type + data
            val sectorsNeeded = (totalLength + 4 + 4095) / 4096  // +4 for length field

            // Find a free sector or append
            val sectorOffset = findFreeSector(sectorsNeeded)
            val byteOffset = sectorOffset * 4096L

            file.seek(byteOffset)

            // Write chunk length (including compression type byte)
            val lengthBuffer = ByteBuffer.allocate(4).putInt(totalLength).array()
            file.write(lengthBuffer)

            // Write compressed data (first byte is compression type)
            file.write(compressedData)

            // Pad to sector boundary
            val written = 4 + totalLength
            val padding = sectorsNeeded * 4096 - written
            if (padding > 0) {
                file.write(ByteArray(padding))
            }

            // Update location table
            val locationOffset = 4 * (localX + localZ * 32)
            file.seek(locationOffset.toLong())
            val locationBytes = ByteArray(4)
            locationBytes[0] = ((sectorOffset shr 16) and 0xFF).toByte()
            locationBytes[1] = ((sectorOffset shr 8) and 0xFF).toByte()
            locationBytes[2] = (sectorOffset and 0xFF).toByte()
            locationBytes[3] = sectorsNeeded.toByte()
            file.write(locationBytes)

            // Update timestamp
            val timestampOffset = locationOffset + 4096
            file.seek(timestampOffset.toLong())
            val timestampBuffer = ByteBuffer.allocate(4).putInt((System.currentTimeMillis() / 1000).toInt()).array()
            file.write(timestampBuffer)

            Logger.d("Written chunk ($localX, $localZ) at sector $sectorOffset ($sectorsNeeded sectors)")
            true
        } catch (e: Exception) {
            Logger.e("Failed to write chunk at ($localX, $localZ)", e)
            false
        }
    }

    /**
     * Mark a chunk as empty (zero out its location entry).
     */
    fun deleteChunk(localX: Int, localZ: Int): Boolean {
        val file = raf ?: return false
        return try {
            val locationOffset = 4 * (localX + localZ * 32)
            file.seek(locationOffset.toLong())
            file.write(ByteArray(4))  // Zero out location
            Logger.d("Deleted chunk ($localX, $localZ)")
            true
        } catch (e: Exception) {
            Logger.e("Failed to delete chunk at ($localX, $localZ)", e)
            false
        }
    }

    private fun findFreeSector(sectorsNeeded: Int): Int {
        val file = raf ?: return 2  // Start at sector 2 (after headers)
        return try {
            val fileLength = file.length()
            // Simple strategy: append at end of file
            val endSector = (fileLength / 4096).toInt()
            if (endSector < 2) 2 else endSector
        } catch (_: Exception) {
            2
        }
    }

    fun close() {
        try {
            raf?.close()
        } catch (_: Exception) { }
        raf = null
    }
}