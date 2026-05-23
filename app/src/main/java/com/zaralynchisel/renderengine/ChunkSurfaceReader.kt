package com.zaralynchisel.renderengine

import com.zaralynchisel.editioncore.NbtReader
import com.zaralynchisel.utils.Logger
import java.io.ByteArrayInputStream

/**
 * Reads the surface block of each column in a chunk from NBT data.
 * Returns a 16×16 array of MapColor IDs.
 * Falls back to height-only gradient if block states can't be parsed.
 */
object ChunkSurfaceReader {

    /** Result: 16x16 array of MapColor IDs. 0 = no block (air/empty). */
    fun readSurface(chunkNbt: ByteArray): Array<IntArray> {
        val result = Array(16) { IntArray(16) { 0 } }
        try {
            val reader = NbtReader(ByteArrayInputStream(chunkNbt))
            val (_, root) = reader.readRoot()
            reader.close()

            // Read heightmap
            val heightmaps = root.getCompound("Heightmaps") ?: root
            val motionBlocking = heightmaps.getList("MOTION_BLOCKING")
            var heights: LongArray? = null
            if (motionBlocking != null) {
                // MOTION_BLOCKING is a packed long array of 16*16 = 256 values, 9 bits each
                val longs = motionBlocking.value
                    .filterIsInstance<NbtReader.NbtTag.NbtLong>()
                    .map { it.value }
                    .toLongArray()
                heights = decodeHeightmap(longs, 9, 256)
            }

            // Read sections to get block at each surface position
            val sections = root.getList("sections") ?: return heightGradient(heights)
            val sectionList = sections.value
                .filterIsInstance<NbtReader.NbtTag.NbtCompound>()
                .sortedBy { it.getInt("Y") }

            if (sectionList.isEmpty()) return heightGradient(heights)

            // For each column
            for (x in 0 until 16) {
                for (z in 0 until 16) {
                    val index = z * 16 + x
                    val surfaceY = heights?.get(index)?.toInt() ?: 64
                    val sectionIndex = surfaceY shr 4

                    // Find the section
                    val section = sectionList.find { it.getInt("Y") == sectionIndex }
                        ?: continue

                    val blockY = surfaceY and 15  // Y within section (0-15)
                    val blockState = parseBlockAt(section, x, blockY, z)
                    val mapColorId = MapColorPalette.getMapColorId(blockState)
                    result[x][z] = mapColorId
                }
            }

        } catch (e: Exception) {
            Logger.e("Failed to read chunk surface", e)
        }
        return result
    }

    /**
     * Fallback: gradient based on height only.
     */
    private fun heightGradient(heights: LongArray?): Array<IntArray> {
        val result = Array(16) { IntArray(16) }
        if (heights == null) return result
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val h = heights[z * 16 + x].toInt()
                // Map height to color: low=plant(7), mid=grass(1), higher=dirt(10), high=stone(11), top=snow(8)
                result[x][z] = when {
                    h < 55 -> 7   // deep / plant
                    h < 64 -> 1   // grass level
                    h < 75 -> 10  // dirt / hills
                    h < 100 -> 11 // stone / mountains
                    else -> 8     // snow peaks
                }
            }
        }
        return result
    }

    /**
     * Parse the block state name at a given position within a section.
     */
    private fun parseBlockAt(
        section: NbtReader.NbtTag.NbtCompound,
        x: Int, y: Int, z: Int
    ): String {
        val blockStates = section.getCompound("block_states") ?: return "air"
        val palette = blockStates.getList("palette") ?: return "air"
        val data = blockStates.getList("data")

        // Single palette entry -> all blocks are the same
        if (palette.value.size == 1) {
            val entry = palette.value[0] as? NbtReader.NbtTag.NbtCompound
            return entry?.getString("Name") ?: "air"
        }

        // If no data array, can't determine
        if (data == null) {
            val entry = palette.value.firstOrNull() as? NbtReader.NbtTag.NbtCompound
            return entry?.getString("Name") ?: "air"
        }

        // Decode palette index from long array data
        val longs = data.value.filterIsInstance<NbtReader.NbtTag.NbtLong>().map { it.value }
        if (longs.isEmpty()) return "air"

        val bitsPerEntry = maxOf(4, 32 - Integer.numberOfLeadingZeros(palette.value.size - 1))
        val blockIndex = y * 256 + z * 16 + x  // Within 16x16x16 section
        val paletteIndex = readBits(longs.toLongArray(), blockIndex, bitsPerEntry)

        if (paletteIndex >= palette.value.size) return "air"
        val entry = palette.value[paletteIndex] as? NbtReader.NbtTag.NbtCompound
        return entry?.getString("Name") ?: "air"
    }

    /**
     * Decode packed heightmap long array (compact bits).
     */
    private fun decodeHeightmap(longs: LongArray, bitsPerEntry: Int, entryCount: Int): LongArray {
        val result = LongArray(entryCount)
        for (i in result.indices) {
            result[i] = readBits(longs, i, bitsPerEntry)
        }
        return result
    }

    /**
     * Read a value at given index from a packed long array with specified bits per entry.
     */
    private fun readBits(longs: LongArray, index: Int, bitsPerEntry: Int): Long {
        val bitOffset = index * bitsPerEntry
        val longIndex = bitOffset / 64
        val bitInLong = bitOffset % 64

        if (longIndex >= longs.size) return 0

        var value = longs[longIndex] ushr bitInLong
        if (bitInLong + bitsPerEntry > 64 && longIndex + 1 < longs.size) {
            val nextBits = longs[longIndex + 1] shl (64 - bitInLong)
            value = value or (nextBits and ((1L shl bitsPerEntry) - 1))
        }
        return value and ((1L shl bitsPerEntry) - 1)
    }
}