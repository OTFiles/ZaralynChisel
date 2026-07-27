package com.zaralynchisel.renderengine

import com.zaralynchisel.editioncore.DimensionType
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

    /** How many blocks below the heightmap top to scan for a non-air surface block. */
    private const val SURFACE_SCAN_DEPTH = 4

    fun readSurface(
        chunkNbt: ByteArray,
        dimension: DimensionType = DimensionType.OVERWORLD
    ): Array<IntArray> {
        val result = Array(16) { IntArray(16) { 0 } }
        val doLog = logCount < 3
        try {
            val reader = NbtReader(ByteArrayInputStream(chunkNbt))
            val (rootName, root) = reader.readRoot()
            reader.close()

            if (doLog) Logger.i("ChunkSurface: rootTag=$rootName dim=$dimension")

            // World minimum Y. The overworld became -64 in 1.18 (DataVersion 2825+); the nether
            // and end are 0. Heightmap values are stored relative to this minY (matches BlueMap,
            // which adds dimensionType.getMinY() when reading heightmaps).
            val dataVersion = root.getInt("DataVersion", 0)
            val worldMinY = when (dimension) {
                DimensionType.OVERWORLD -> if (dataVersion >= 2825) -64 else 0
                DimensionType.NETHER -> 0
                DimensionType.END -> 0
            }

            // Read heightmap. MOTION_BLOCKING is a TAG_Long_Array in all modern chunk formats.
            val heightmapsCompound = root.getCompound("Heightmaps")
            val usingRoot = heightmapsCompound == null
            val heightmaps = heightmapsCompound ?: root
            val motionBlocking = heightmaps.getLongArray("MOTION_BLOCKING")

            if (doLog) {
                val keys = heightmaps.value.keys.take(10).joinToString(",")
                Logger.i("ChunkSurface: usingRoot=$usingRoot hmKeys=[$keys] mbLongArray=${motionBlocking != null}")
            }
            // Heightmap entries are 9 bits for standard dimensions (worldHeight <= 384).
            // Convert to absolute surface-block Y per column. The stored value is relative to
            // worldMinY and points at the first non-blocking block above the surface, so the
            // surface block is at (stored + worldMinY - 1).
            val heightsAbs: IntArray? = motionBlocking?.let { mb ->
                val rel = decodeHeightmap(mb, 9, 256)
                IntArray(256) { i -> rel[i].toInt() + worldMinY - 1 }
            }

            if (doLog && heightsAbs != null) {
                Logger.i("ChunkSurface: heights decoded, sample[0..4]=${heightsAbs.take(5).joinToString()}")
            }

            // Read sections to get block at each surface position
            val sections = root.getList("sections") ?: return heightGradient(heightsAbs)
            val sectionList = sections.value
                .filterIsInstance<NbtReader.NbtTag.NbtCompound>()
                .sortedBy { it.getInt("Y") }

            if (sectionList.isEmpty()) return heightGradient(heightsAbs)

            val sectionByY = sectionList.associateBy { it.getInt("Y") }
            val sectionsDesc = sectionList.sortedByDescending { it.getInt("Y") }

            if (doLog) {
                val status = root.getString("Status", "?")
                Logger.i("ChunkSurface: sections=${sectionList.size} worldMinY=$worldMinY sectionYs=${sectionList.take(3).map { it.getInt("Y") }}..${sectionList.takeLast(3).map { it.getInt("Y") }} status=$status")
            }

            // For each column
            for (x in 0 until 16) {
                for (z in 0 until 16) {
                    if (heightsAbs != null) {
                        val index = z * 16 + x
                        val topY = heightsAbs[index].coerceAtLeast(worldMinY)
                        // Scan down a few blocks to robustly resolve the topmost non-air block
                        // (absorbs any +/-1 ambiguity in the heightmap definition).
                        for (dy in 0 until SURFACE_SCAN_DEPTH) {
                            val y = topY - dy
                            if (y < worldMinY) break
                            val section = sectionByY[y shr 4] ?: continue
                            val blockY = y and 15
                            val cid = MapColorPalette.getMapColorId(parseBlockAt(section, x, blockY, z))
                            if (cid > 0) {
                                result[x][z] = cid
                                break
                            }
                        }
                    } else {
                        // No heightmap → scan sections top-down for the first non-air block.
                        for (section in sectionsDesc) {
                            var found = false
                            for (y in 15 downTo 0) {
                                val cid = MapColorPalette.getMapColorId(parseBlockAt(section, x, y, z))
                                if (cid > 0) {
                                    result[x][z] = cid
                                    found = true
                                    break
                                }
                            }
                            if (found) break
                        }
                    }
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
     * Fallback: gradient based on absolute height only.
     */
    private fun heightGradient(heightsAbs: IntArray?): Array<IntArray> {
        val result = Array(16) { IntArray(16) }
        if (heightsAbs == null) return result
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val h = heightsAbs[z * 16 + x]
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

        // Decode palette index from packed long array.
        // Modern (1.16+) chunk sections use per-long packing: each 64-bit long holds
        // floor(64/bits) entries and entries never span long boundaries (matches BlueMap's
        // PackedIntArrayAccess). This is NOT a continuous bitstream.
        val bitsPerEntry = maxOf(4, 32 - Integer.numberOfLeadingZeros(palette.value.size - 1))
        val blockIndex = y * 256 + z * 16 + x  // Within 16x16x16 section (YZX ordering)
        val paletteIndex = readPackedLong(data, blockIndex, bitsPerEntry)

        if (paletteIndex < 0 || paletteIndex >= palette.value.size) return "air"
        val entry = palette.value[paletteIndex] as? NbtReader.NbtTag.NbtCompound
        return entry?.getString("Name") ?: "air"
    }

    /**
     * Read a value from a per-long packed array (the format used by both heightmaps and
     * block_states in 1.16+ chunks). Each 64-bit long holds `floor(64/bits)` entries and
     * entries never span long boundaries; leftover high bits of the last entry slot in each
     * long are unused.
     */
    private fun readPackedLong(longs: LongArray, index: Int, bitsPerEntry: Int): Int {
        if (bitsPerEntry <= 0 || bitsPerEntry > 64) return 0
        val entriesPerLong = 64 / bitsPerEntry
        if (entriesPerLong <= 0) return 0
        val storageIndex = index / entriesPerLong
        if (storageIndex < 0 || storageIndex >= longs.size) return 0
        val offset = (index - storageIndex * entriesPerLong) * bitsPerEntry
        val mask = (1L shl bitsPerEntry) - 1L
        return ((longs[storageIndex] ushr offset) and mask).toInt()
    }

    /**
     * Decode a packed heightmap long array (per-long packing, see [readPackedLong]).
     */
    private fun decodeHeightmap(longs: LongArray, bitsPerEntry: Int, entryCount: Int): LongArray {
        val result = LongArray(entryCount)
        for (i in 0 until entryCount) {
            result[i] = readPackedLong(longs, i, bitsPerEntry).toLong()
        }
        return result
    }
}