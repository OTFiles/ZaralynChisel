package com.zaralynchisel.editioncore

import com.zaralynchisel.renderengine.ChunkSurfaceReader
import com.zaralynchisel.utils.Logger
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Reads Minecraft Anvil (.mca) region files.
 */
class AnvilReader(private val regionFile: File) {

    private var raf: RandomAccessFile? = null
    private var tempFile: File? = null

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
            val reader = AnvilReader(tempFile)
            reader.tempFile = tempFile
            return reader
        }

        private fun decodeHeightmap(longs: LongArray, bitsPerEntry: Int, entryCount: Int): IntArray {
            val result = IntArray(entryCount)
            for (i in result.indices) {
                val bitOffset = i * bitsPerEntry
                val longIndex = bitOffset / 64
                val bitInLong = bitOffset % 64
                var value = longs[longIndex] ushr bitInLong
                if (bitInLong + bitsPerEntry > 64 && longIndex + 1 < longs.size) {
                    value = value or (longs[longIndex + 1] shl (64 - bitInLong))
                }
                result[i] = (value and ((1L shl bitsPerEntry) - 1)).toInt()
            }
            return result
        }
    }

    /**
     * Open the region file for reading.
     */
    fun open(): Boolean {
        return try {
            if (!regionFile.exists() || !regionFile.isFile) {
                Logger.e("Region file not found: ${regionFile.absolutePath}")
                return false
            }
            raf = RandomAccessFile(regionFile, "r")
            Logger.d("Opened region: ${regionFile.name}")
            true
        } catch (e: Exception) {
            Logger.e("Failed to open region file", e)
            false
        }
    }

    /**
     * Read chunk header (location and timestamp) for a given chunk coordinate.
     * Chunk coordinates are local to this region (0-31).
     */
    fun readChunkHeader(localX: Int, localZ: Int): ChunkHeader? {
        val file = raf ?: return null
        return try {
            val offset = 4 * (localX + localZ * 32)

            // Read location (4 bytes: 3 bytes offset, 1 byte sector count)
            file.seek(offset.toLong())
            val locationBuffer = ByteArray(4)
            file.readFully(locationBuffer)
            val sectorOffset = ((locationBuffer[0].toInt() and 0xFF) shl 16) or
                    ((locationBuffer[1].toInt() and 0xFF) shl 8) or
                    (locationBuffer[2].toInt() and 0xFF)
            val sectorCount = locationBuffer[3].toInt() and 0xFF

            // Read timestamp (4 bytes)
            file.seek((offset + 4096).toLong())
            val timestampBuffer = ByteArray(4)
            file.readFully(timestampBuffer)
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
     * Read the raw compressed chunk data.
     */
    fun readChunkData(localX: Int, localZ: Int): ByteArray? {
        val header = readChunkHeader(localX, localZ) ?: return null
        if (header.sectorOffset == 0 || header.sectorCount == 0) return null

        val file = raf ?: return null
        return try {
            val byteOffset = header.sectorOffset * 4096L
            file.seek(byteOffset)

            // Read chunk length (4 bytes) + compression type (1 byte)
            val lengthBuffer = ByteArray(4)
            file.readFully(lengthBuffer)
            val chunkLength = ByteBuffer.wrap(lengthBuffer).int

            if (chunkLength <= 1) return null

            val compressionType = file.readByte().toInt() and 0xFF
            val dataLength = chunkLength - 1  // minus compression type byte

            val data = ByteArray(dataLength)
            file.readFully(data)

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
    fun readChunkSurface(localX: Int, localZ: Int): IntArray? {
        val data = readChunkData(localX, localZ) ?: return null
        return try {
            val surface = ChunkSurfaceReader.readSurface(data)
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
     * Read the heightmap (MOTION_BLOCKING) from chunk data.
     * This is much faster than parsing all block states.
     * Returns 256-entry height array (16×16 in row-major: z*16+x).
     */
    fun readChunkHeightmap(localX: Int, localZ: Int): IntArray? {
        val data = readChunkData(localX, localZ) ?: return null
        return try {
            val reader = NbtReader(ByteArrayInputStream(data))
            val (_, root) = reader.readRoot()
            reader.close()

            val heightmaps = root.getCompound("Heightmaps") ?: return null
            val motionBlocking = heightmaps.getList("MOTION_BLOCKING") ?: return null
            val longs = motionBlocking.value
                .filterIsInstance<NbtReader.NbtTag.NbtLong>()
                .map { it.value }
                .toLongArray()

            decodeHeightmap(longs, 9, 256)
        } catch (e: Exception) {
            Logger.e("Failed to read heightmap for ($localX, $localZ)", e)
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
            raf?.close()
        } catch (_: Exception) { }
        raf = null
    }

    data class ChunkHeader(
        val localX: Int,
        val localZ: Int,
        val sectorOffset: Int,
        val sectorCount: Int,
        val timestamp: Long
    )
}