package com.zaralynchisel.editioncore

import com.zaralynchisel.renderengine.ChunkSurfaceReader
import com.zaralynchisel.utils.Logger
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Reads Minecraft Anvil (.mca) region files.
 *
 * Uses java.nio FileChannel for random access, which supports three zero-copy
 * open modes (direct File, SAF ParcelFileDescriptor, temp-file fallback).
 */
class AnvilReader private constructor(
    private val regionFile: File?,
    private val preopenedChannel: FileChannel?,
    private val ownedPfd: android.os.ParcelFileDescriptor?
) {

    private var channel: FileChannel? = null
    private var tempFile: File? = null

    constructor(regionFile: File) : this(regionFile, null, null)

    companion object {
        /**
         * Create an AnvilReader from an InputStream (e.g. from SAF ContentResolver).
         * Buffers the entire stream to a temp file for random access.
         */
        fun fromStream(stream: InputStream): AnvilReader {
            val tempFile = File.createTempFile("region_", ".mca")
            tempFile.deleteOnExit()
            tempFile.outputStream().use { out ->
                stream.copyTo(out)
            }
            val reader = AnvilReader(tempFile, null, null)
            reader.tempFile = tempFile
            return reader
        }

        /**
         * Create an AnvilReader over a seekable ParcelFileDescriptor (zero-copy —
         * no temp-file copy). The reader takes ownership of the PFD and closes it
         * in [close].
         */
        fun fromParcelFileDescriptor(pfd: android.os.ParcelFileDescriptor): AnvilReader {
            val fis = FileInputStream(pfd.fileDescriptor)
            return AnvilReader(null, fis.channel, pfd)
        }
    }

    /**
     * Open the region file for reading.
     */
    fun open(): Boolean {
        return try {
            channel = preopenedChannel
            if (channel == null) {
                val file = regionFile ?: return false
                if (!file.exists() || !file.isFile) {
                    Logger.e("Region file not found: ${file.absolutePath}")
                    return false
                }
                channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            }
            Logger.d("Opened region: ${regionFile?.name ?: "fd"}")
            true
        } catch (e: Exception) {
            Logger.e("Failed to open region file", e)
            false
        }
    }

    /**
     * Position the channel at [at] and read [buffer] fully (loops until the
     * buffer is filled or EOF). Returns false on EOF/error.
     */
    private fun readFullyAt(at: Long, buffer: ByteArray): Boolean {
        val ch = channel ?: return false
        return try {
            ch.position(at)
            val bb = ByteBuffer.wrap(buffer)
            while (bb.hasRemaining()) {
                if (ch.read(bb) < 0) return false
            }
            true
        } catch (e: Exception) {
            Logger.e("readFullyAt failed at $at", e)
            false
        }
    }

    /**
     * Read chunk header (location and timestamp) for a given chunk coordinate.
     * Chunk coordinates are local to this region (0-31).
     */
    fun readChunkHeader(localX: Int, localZ: Int): ChunkHeader? {
        return try {
            val offset = 4L * (localX + localZ * 32)

            // Read location (4 bytes: 3 bytes offset, 1 byte sector count)
            val locationBuffer = ByteArray(4)
            if (!readFullyAt(offset, locationBuffer)) return null
            val sectorOffset = ((locationBuffer[0].toInt() and 0xFF) shl 16) or
                    ((locationBuffer[1].toInt() and 0xFF) shl 8) or
                    (locationBuffer[2].toInt() and 0xFF)
            val sectorCount = locationBuffer[3].toInt() and 0xFF

            // Read timestamp (4 bytes)
            val timestampBuffer = ByteArray(4)
            if (!readFullyAt(offset + 4096, timestampBuffer)) return null
            val timestamp = ByteBuffer.wrap(timestampBuffer).int.toLong() and 0xFFFFFFFFL

            ChunkHeader(
                localX = localX,
                localZ = localZ,
                sectorOffset = sectorOffset,
                sectorCount = sectorCount,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Logger.e("Failed to read chunk header at ($localX, $localZ)", e)
            null
        }
    }

    /**
     * Read the raw compressed chunk data (compression-type byte omitted).
     */
    fun readChunkData(localX: Int, localZ: Int): ByteArray? {
        val header = readChunkHeader(localX, localZ) ?: return null
        if (header.sectorOffset == 0 || header.sectorCount == 0) return null

        return try {
            val byteOffset = header.sectorOffset * 4096L

            // Read chunk length (4 bytes) — includes the compression type byte.
            val lengthBuffer = ByteArray(4)
            if (!readFullyAt(byteOffset, lengthBuffer)) return null
            val chunkLength = ByteBuffer.wrap(lengthBuffer).int

            if (chunkLength <= 1) return null

            // Data = chunkLength - 1 bytes at byteOffset + 5 (skip length + compression type)
            val data = ByteArray(chunkLength - 1)
            if (!readFullyAt(byteOffset + 5, data)) return null
            data
        } catch (e: Exception) {
            Logger.e("Failed to read chunk data at ($localX, $localZ)", e)
            null
        }
    }

    /**
     * Read the surface MapColor data for a chunk.
     * Returns 256-entry IntArray (z*16+x indexing) of MapColor IDs.
     */
    fun readChunkSurface(localX: Int, localZ: Int, dimension: DimensionType = DimensionType.OVERWORLD): IntArray? {
        val data = readChunkData(localX, localZ) ?: return null
        return try {
            val surface = ChunkSurfaceReader.readSurface(data, dimension)
            val result = IntArray(256)
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    result[z * 16 + x] = surface[x][z]
                }
            }
            result
        } catch (e: Exception) {
            Logger.e("Failed to read chunk surface", e)
            null
        }
    }

    /**
     * Get all non-empty chunk positions in this region.
     */
    fun listChunks(): List<Pair<Int, Int>> {
        val chunks = mutableListOf<Pair<Int, Int>>()
        for (x in 0 until 32) {
            for (z in 0 until 32) {
                val header = readChunkHeader(x, z)
                if (header != null && header.sectorOffset != 0 && header.sectorCount != 0) {
                    chunks.add(Pair(x, z))
                }
            }
        }
        return chunks
    }

    fun close() {
        try {
            channel?.close()
        } catch (_: Exception) { }
        channel = null
        try {
            ownedPfd?.close()
        } catch (_: Exception) { }
        try {
            tempFile?.delete()
        } catch (_: Exception) { }
        tempFile = null
    }

    data class ChunkHeader(
        val localX: Int,
        val localZ: Int,
        val sectorOffset: Int,
        val sectorCount: Int,
        val timestamp: Long
    )
}
