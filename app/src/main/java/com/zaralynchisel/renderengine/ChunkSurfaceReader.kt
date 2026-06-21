package com.zaralynchisel.renderengine

import com.zaralynchisel.editioncore.NbtReader
import com.zaralynchisel.editioncore.getCompound
import com.zaralynchisel.editioncore.getInt
import com.zaralynchisel.editioncore.getList
import com.zaralynchisel.editioncore.getLongArray
import com.zaralynchisel.editioncore.getString
import com.zaralynchisel.utils.Logger
import java.io.ByteArrayInputStream

/**
 * Reads the surface block of each column in a chunk from NBT data.
 * Returns a 16×16 array of MapColor IDs.
 * Falls back to height-only gradient if block states can't be parsed.
 */
object ChunkSurfaceReader {

    /** Result: 16x16 array of MapColor IDs. 0 = no block (air/empty). */
    private var logCount = 0

    fun readSurface(chunkNbt: ByteArray): Array<IntArray> {
        val result = Array(16) { IntArray(16) { 0 } }
        val doLog = logCount < 3
        try {
            val reader = NbtReader(ByteArrayInputStream(chunkNbt))
            val (rootName, root) = reader.readRoot()
            reader.close()

            if (doLog) Logger.i("ChunkSurface: rootTag=$rootName")

            // Read heightmap (TAG_Long_Array in 1.18+, but some chunks may use TAG_List)
            val heightmapsCompound = root.getCompound("Heightmaps")
            val usingRoot = heightmapsCompound == null
            val heightmaps = heightmapsCompound ?: root
            var motionBlocking = heightmaps.getLongArray("MOTION_BLOCKING")

            if (doLog) {
                val keys = heightmaps.value.keys.take(10).joinToString(",")
                Logger.i("ChunkSurface: usingRoot=$usingRoot hmKeys=[$keys] mbLongArray=${motionBlocking != null}")
            }
            var heights: LongArray? = null
            if (motionBlocking != null) {
                heights = decodeHeightmap(motionBlocking, 9, 256)
            } else {
                // Fallback: try TAG_List (pre-1.18 chunk format)
                val mbList = heightmaps.getList("MOTION_BLOCKING")
                if (mbList != null) {
                    if (doLog) Logger.i("ChunkSurface: MOTION_BLOCKING is TAG_List, not TAG_Long_Array — decoding from list")
                    val longs = mbList.value
                        .filterIsInstance<NbtReader.NbtTag.NbtLong>()
                        .map { it.value }
                        .toLongArray()
                    motionBlocking = longs
                    heights = decodeHeightmap(longs, 9, 256)
                }
            }

            if (doLog && heights != null) {
                Logger.i("ChunkSurface: heights decoded, sample[0..4]=${heights.take(5).joinToString()}")
            }

            // Read sections to get block at each surface position
            val sections = root.getList("sections") ?: return heightGradient(heights)
            val sectionList = sections.value
                .filterIsInstance<NbtReader.NbtTag.NbtCompound>()
                .sortedBy { it.getInt("Y") }

            if (sectionList.isEmpty()) return heightGradient(heights)

            if (doLog) Logger.i("ChunkSurface: sections=${sectionList.size} sectionYs=${sectionList.take(3).map { it.getInt("Y") }}..${sectionList.takeLast(3).map { it.getInt("Y") }}")

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
            // Log sample of parsed surface
            val sample = listOf(result[0][0], result[8][0], result[0][8], result[15][15], result[8][8])
            val nonZero = result.sumOf { row -> row.count { it > 0 } }
            if (doLog) Logger.i("ChunkSurface: sample=$sample nonZeroCells=$nonZero/256")
            logCount++

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
        // data is TAG_Long_Array (packed long array), not TAG_List
        val data = blockStates.getLongArray("data")

        // Single palette entry -> all blocks are the same
        if (palette.value.size == 1) {
            val entry = palette.value[0] as? NbtReader.NbtTag.NbtCompound
            return entry?.getString("Name") ?: "air"
        }

        // If no data array, can't determine
        if (data == null || data.isEmpty()) {
            val entry = palette.value.firstOrNull() as? NbtReader.NbtTag.NbtCompound
            return entry?.getString("Name") ?: "air"
        }

        // Decode palette index from packed long array
        val bitsPerEntry = maxOf(4, 32 - Integer.numberOfLeadingZeros(palette.value.size - 1))
        val blockIndex = y * 256 + z * 16 + x  // Within 16x16x16 section
        val paletteIndex = readBits(data, blockIndex, bitsPerEntry).toInt()

        if (paletteIndex >= palette.value.size) return "air"
        val entry = palette.value[paletteIndex] as? NbtReader.NbtTag.NbtCompound
        return entry?.getString("Name") ?: "air"
    }

    /**
     * Decode packed heightmap long array.
     * MOTION_BLOCKING stores entries per-long with no cross-long overflow:
     * each 64-bit long holds entriesPerLong = 64/bitsPerEntry entries
     * (e.g. 9-bit entries → 7 per long, 63 used bits + 1 zero pad).
     * This is DIFFERENT from the continuous bitstream used in block_states.
     */
    private fun decodeHeightmap(longs: LongArray, bitsPerEntry: Int, entryCount: Int): LongArray {
        val result = LongArray(entryCount)
        val entriesPerLong = 64 / bitsPerEntry
        val mask = (1L shl bitsPerEntry) - 1
        for (longIdx in longs.indices) {
            val base = longIdx * entriesPerLong
            for (entryIdx in 0 until entriesPerLong) {
                val globalIdx = base + entryIdx
                if (globalIdx >= entryCount) break
                result[globalIdx] = (longs[longIdx] ushr (entryIdx * bitsPerEntry)) and mask
            }
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