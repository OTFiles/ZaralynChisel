package com.zaralynchisel.editioncore

import com.zaralynchisel.renderengine.ChunkSurfaceReader
import com.zaralynchisel.utils.Logger
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

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

        /** Bytes of the NBT string "WORLD_SURFACE", present only in generated chunks
         *  (heightmaps are populated at the "heightmaps" generation stage). */
        private val WORLD_SURFACE_BYTES = "WORLD_SURFACE".toByteArray(Charsets.UTF_8)
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
     * Cheaply test whether a chunk has generated terrain without parsing NBT.
     * Pre-noise stubs ("structure_starts") contain no heightmaps; once a chunk
     * reaches the "heightmaps" stage it stores a WORLD_SURFACE heightmap. So we
     * decompress the chunk and scan the raw bytes for the "WORLD_SURFACE"
     * marker. ~5ms per chunk vs ~90ms for a full NBT parse.
     */
    fun hasTerrain(localX: Int, localZ: Int): Boolean {
        val data = readChunkData(localX, localZ) ?: return false
        return try {
            val decompressed = decompressToBytes(data) ?: return false
            // "WORLD_SURFACE" only exists in post-heightmaps (i.e. generated) chunks.
            indexOfBytes(decompressed, WORLD_SURFACE_BYTES) >= 0
        } catch (e: Exception) {
            Logger.e("hasTerrain failed at ($localX,$localZ)", e)
            false
        }
    }

    private fun decompressToBytes(data: ByteArray): ByteArray? {
        val raw = when {
            data.size >= 2 && (data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) ->
                GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            data.size >= 1 && data[0] == 0x78.toByte() ->
                InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            else -> data
        }
        return raw
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            var j = 0
            while (j < needle.size) {
                if (haystack[i + j] != needle[j]) continue@outer
                j++
            }
            return i
        }
        return -1
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