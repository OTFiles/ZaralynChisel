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

    /** How many blocks below the surface are recorded per column (for cliff walls). */
    private const val BELOW_SURFACE_SCAN_DEPTH = 40

    /** Per-column surface data: 16×16 MapColor IDs, absolute surface Y, and the block
     *  names needed to texture the terrain (surface block + stack of blocks below it). */
    class SurfaceData(
        val colors: Array<IntArray>,   // [x][z] MapColor id (0 = air)
        val heights: Array<IntArray>,  // [x][z] absolute surface Y, Int.MIN_VALUE if none
        /** [x][z] → block name at the surface ("minecraft:grass_block"), null if none. */
        val surfaceBlocks: Array<Array<String?>>,
        /** [x][z] → block names below the surface: index 0 = the block just below the
         *  surface, going down, up to [BELOW_SURFACE_SCAN_DEPTH] entries. Only solid
         *  blocks are recorded (scan stops at the first air). Empty if no surface. */
        val belowSurface: Array<Array<Array<String?>>>
    )

    /** Cached palette lookup for one section (avoids re-parsing palette + data per call). */
    private class SectionBlocks(
        val names: List<String>,
        val bits: Int,
        val data: LongArray?
    ) {
        fun blockAt(x: Int, y: Int, z: Int): String {
            if (names.size == 1) return names[0]
            val d = data ?: return names.firstOrNull() ?: "air"
            val index = y * 256 + z * 16 + x
            val pi = readPackedLong(d, index, bits)
            return names.getOrNull(pi) ?: "air"
        }

        companion object {
            fun from(section: NbtReader.NbtTag.NbtCompound): SectionBlocks {
                val blockStates = section.getCompound("block_states")
                val palette = blockStates?.getList("palette")
                val names = palette?.value?.mapNotNull {
                    (it as? NbtReader.NbtTag.NbtCompound)?.getString("Name")
                } ?: emptyList()
                val data = blockStates?.getLongArray("data")
                val bits = maxOf(4, 32 - Integer.numberOfLeadingZeros(names.size - 1))
                return SectionBlocks(names, bits, data)
            }
        }
    }

    fun readSurface(
        chunkNbt: ByteArray,
        dimension: DimensionType = DimensionType.OVERWORLD
    ): Array<IntArray> = readSurfaceData(chunkNbt, dimension).colors

    fun readSurfaceData(
        chunkNbt: ByteArray,
        dimension: DimensionType = DimensionType.OVERWORLD
    ): SurfaceData {
        val colors = Array(16) { IntArray(16) { 0 } }
        val heights = Array(16) { IntArray(16) { Int.MIN_VALUE } }
        val surfaceBlocks = Array(16) { arrayOfNulls<String>(16) }
        val belowSurface = Array(16) { Array(16) { arrayOfNulls<String>(0) } }
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

            val sectionByY = HashMap<Int, SectionBlocks>()
            for (s in sectionList) {
                sectionByY[s.getInt("Y")] = SectionBlocks.from(s)
            }
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
                            val name = section.blockAt(x, blockY, z)
                            val cid = MapColorPalette.getMapColorId(name)
                            if (cid > 0) {
                                colors[x][z] = cid
                                heights[x][z] = y
                                surfaceBlocks[x][z] = name
                                break
                            }
                        }
                    } else {
                        // No heightmap → scan sections top-down for the first non-air block.
                        for (section in sectionsDesc) {
                            var found = false
                            for (y in 15 downTo 0) {
                                val name = sectionByY[section.getInt("Y")]!!.blockAt(x, y, z)
                                val cid = MapColorPalette.getMapColorId(name)
                                if (cid > 0) {
                                    colors[x][z] = cid
                                    heights[x][z] = section.getInt("Y") * 16 + y
                                    surfaceBlocks[x][z] = name
                                    found = true
                                    break
                                }
                            }
                            if (found) break
                        }
                    }
                }
            }

            // Record the solid block stack below each surface column (for cliff walls).
            // Stops at the first air block (cave); deeper gaps are filled with stone
            // by the mesh builder.
            for (x in 0 until 16) {
                for (z in 0 until 16) {
                    val top = heights[x][z]
                    if (top == Int.MIN_VALUE) continue
                    val stack = ArrayList<String>(BELOW_SURFACE_SCAN_DEPTH)
                    for (dy in 1..BELOW_SURFACE_SCAN_DEPTH) {
                        val y = top - dy
                        if (y < worldMinY) break
                        val section = sectionByY[y shr 4] ?: break
                        val name = section.blockAt(x, y and 15, z)
                        if (name.endsWith("air")) break
                        stack.add(name)
                    }
                    belowSurface[x][z] = stack.toTypedArray()
                }
            }
            // Log sample of parsed surface
            val sample = listOf(colors[0][0], colors[8][0], colors[0][8], colors[15][15], colors[8][8])
            val nonZero = colors.sumOf { row -> row.count { it > 0 } }
            if (doLog) Logger.i("ChunkSurface: sample=$sample nonZeroCells=$nonZero/256")
            logCount++

        } catch (e: Exception) {
            Logger.e("Failed to read chunk surface", e)
        }
        return SurfaceData(colors, heights, surfaceBlocks, belowSurface)
    }

    /**
     * Fallback: gradient based on absolute height only (heights unknown).
     */
    private fun heightGradient(heightsAbs: IntArray?): SurfaceData {
        val colors = Array(16) { IntArray(16) }
        val heights = Array(16) { IntArray(16) { Int.MIN_VALUE } }
        val surfaceBlocks = Array(16) { arrayOfNulls<String>(16) }
        val belowSurface = Array(16) { Array(16) { arrayOfNulls<String>(0) } }
        if (heightsAbs == null) return SurfaceData(colors, heights, surfaceBlocks, belowSurface)
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val h = heightsAbs[z * 16 + x]
                // Map height to color: low=plant(7), mid=grass(1), higher=dirt(10), high=stone(11), top=snow(8)
                colors[x][z] = when {
                    h < 55 -> 7   // deep / plant
                    h < 64 -> 1   // grass level
                    h < 75 -> 10  // dirt / hills
                    h < 100 -> 11 // stone / mountains
                    else -> 8     // snow peaks
                }
                heights[x][z] = h
            }
        }
        return SurfaceData(colors, heights, surfaceBlocks, belowSurface)
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

    /** Like [readSurface] but returns a flat IntArray(256) with z*16+x indexing
     *  — same format as [AnvilReader.readChunkSurface]. Caller provides raw
     *  compressed NBT (no compression-type byte). CPU-only; no file I/O. */
    fun readSurfaceFlat(chunkNbt: ByteArray, dimension: DimensionType = DimensionType.OVERWORLD): IntArray? {
        return try {
            val surface2d = readSurface(chunkNbt, dimension)
            val result = IntArray(256)
            for (z in 0 until 16)
                for (x in 0 until 16)
                    result[z * 16 + x] = surface2d[x][z]
            result
        } catch (e: Exception) {
            Logger.e("Failed to decode chunk surface", e)
            null
        }
    }
}