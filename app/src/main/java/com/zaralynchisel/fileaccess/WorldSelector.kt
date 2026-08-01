package com.zaralynchisel.fileaccess

import android.content.Context
import android.net.Uri
import com.zaralynchisel.editioncore.*
import com.zaralynchisel.renderengine.ChunkSurfaceReader
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.PreferenceManager
import com.zaralynchisel.utils.withFileIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Handles world selection and validation.
 * Supports both direct file paths and SAF document trees.
 */
class WorldSelector(private val context: Context) {

    private val prefs = PreferenceManager(context)
    var safAccess: SafFileAccess? = null

    /** One cached region: the reader plus its own lock.  Readers are only ever
     *  touched under [lock], so different regions can be read in parallel while
     *  the same region stays serialised. */
    private class RegionEntry(val reader: com.zaralynchisel.editioncore.AnvilReader, val lock: Mutex)

    /** LRU cache of open region readers (access-order LinkedHashMap). Keeps a few
     *  regions open at once so alternating between them doesn't re-open the file.
     *  Map access must happen inside [readerMutex]; file reads use each entry's
     *  own lock. */
    private val regionEntries = object : LinkedHashMap<Long, RegionEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RegionEntry>?): Boolean {
            if (size > MAX_CACHED_REGIONS) {
                eldest?.value?.reader?.close()
                return true
            }
            return false
        }
    }

    /** Serialises only the LRU map operations (get/put/evict) — these are cheap.
     *  The actual file I/O for a region is guarded by that region's own lock, so
     *  different regions load in parallel. */
    private val readerMutex = Mutex()

    /** Discard all cached region readers so the next surface load re-reads the
     *  region files from disk (needed after a write operation changes them). */
    suspend fun clearReaderCache() {
        readerMutex.withLock {
            regionEntries.values.forEach { it.reader.close() }
            regionEntries.clear()
        }
    }

    /** Read the compressed NBT data for one chunk (no compression-type byte).
     *  Returns null when the chunk doesn't exist on disk (sector=0). The region
     *  reader is looked up under the global mutex (fast), then the file read runs
     *  under that region's own lock, so chunks in different regions load in
     *  parallel while the same region stays serialised. */
    private suspend fun readChunkCompressed(worldPath: String, chunkX: Int, chunkZ: Int, dimension: DimensionType): ByteArray? {
        val entry = getOrOpenRegion(worldPath, chunkX, chunkZ, dimension) ?: return null
        return entry.lock.withLock {
            val lx = chunkX and 31
            val lz = chunkZ and 31
            val header = entry.reader.readChunkHeader(lx, lz)
            if (header == null || header.sectorOffset == 0 || header.sectorCount == 0) return@withLock null
            entry.reader.readChunkData(lx, lz)
        }
    }

    /** Look up (or open + cache) the region entry for the chunk. The LRU map is
     *  guarded by the global mutex; opening a region may do a PFD IPC, so this is
     *  only serialised across regions during the open itself. */
    private suspend fun getOrOpenRegion(worldPath: String, chunkX: Int, chunkZ: Int, dimension: DimensionType): RegionEntry? {
        val regionDir = File(worldPath, dimension.folderName)
        val regionX = chunkX shr 5
        val regionZ = chunkZ shr 5
        val regionFile = File(regionDir, "r.$regionX.$regionZ.mca")
        if (!regionFile.exists()) return null
        val regionKey = (regionX.toLong() shl 32) or (regionZ.toLong() and 0xFFFFFFFFL)
        return readerMutex.withLock {
            regionEntries[regionKey] ?: openRegionReader(worldPath, dimension, regionFile, regionKey)
        }
    }

    /** Open a region reader, preferring zero-copy paths (SAF ParcelFileDescriptor,
     *  then direct RandomAccessFile) and falling back to stream→temp-file copy.
     *  Caller must hold [readerMutex]. */
    private fun openRegionReader(worldPath: String, dimension: DimensionType, regionFile: File, regionKey: Long): RegionEntry? {
        val relPath = "${dimension.folderName}/${regionFile.name}"

        // 1) Zero-copy via SAF ParcelFileDescriptor (seekable, no temp file)
        safAccess?.let { saf ->
            val pfd = saf.openParcelFileDescriptor(relPath, regionFile)
            if (pfd != null) {
                val r = com.zaralynchisel.editioncore.AnvilReader.fromParcelFileDescriptor(pfd)
                if (r.open()) {
                    val e = RegionEntry(r, Mutex())
                    regionEntries[regionKey] = e
                    return e
                }
                r.close()
            }
        }

        // 2) Zero-copy direct file access (path readable without SAF)
        if (regionFile.exists() && regionFile.canRead()) {
            val r = com.zaralynchisel.editioncore.AnvilReader(regionFile)
            if (r.open()) {
                val e = RegionEntry(r, Mutex())
                regionEntries[regionKey] = e
                return e
            }
            r.close()
        }

        // 3) Fallback: stream → temp-file copy
        val stream = safAccess?.openInputStream(relPath, regionFile)
            ?: if (regionFile.exists()) java.io.BufferedInputStream(java.io.FileInputStream(regionFile)) else null
        if (stream == null) return null
        val r = com.zaralynchisel.editioncore.AnvilReader.fromStream(stream)
        if (r.open()) {
            val e = RegionEntry(r, Mutex())
            regionEntries[regionKey] = e
            return e
        }
        r.close()
        return null
    }

    /**
     * Validate that a given path is a valid Minecraft world.
     */
    suspend fun validateWorld(worldPath: String): ValidationResult {
        return withFileIO {
            val dir = File(worldPath)
            Logger.i("Validating world at: $worldPath")
            if (!dir.exists() || !dir.isDirectory) {
                Logger.w("Path does not exist or is not directory: $worldPath")
                return@withFileIO ValidationResult.Invalid("路径不存在或不是文件夹")
            }
            val levelDat = File(dir, "level.dat")
            val levelDatExists = safAccess?.exists("level.dat", levelDat) ?: levelDat.exists()
            if (!levelDatExists) {
                Logger.w("No level.dat found at $worldPath")
                return@withFileIO ValidationResult.Invalid("未找到 level.dat — 不是 Minecraft 存档")
            }
            val hasRegion = DimensionType.entries.any { dim ->
                val dimDir = File(dir, dim.folderName)
                dimDir.exists() || (safAccess?.exists(dim.folderName, dimDir) == true)
            }
            if (!hasRegion) {
                return@withFileIO ValidationResult.Invalid("未找到区域文件 — 存档可能为空或已损坏")
            }
            Logger.i("World validated successfully: $worldPath")
            ValidationResult.Valid(worldPath)
        }
    }

    /**
     * Load world metadata from level.dat using NbtReader.
     */
    suspend fun loadWorldInfo(worldPath: String): WorldData? {
        return withFileIO {
            try {
                val dir = File(worldPath)
                val levelDat = File(dir, "level.dat")

                Logger.i("Reading level.dat from $worldPath")

                val stream: InputStream? = if (safAccess != null) {
                    safAccess!!.openInputStream("level.dat", levelDat)
                } else if (levelDat.exists()) {
                    BufferedInputStream(FileInputStream(levelDat))
                } else {
                    null
                }

                if (stream == null) {
                    Logger.e("Cannot open level.dat at $worldPath")
                    return@withFileIO null
                }

                val reader = NbtReader(stream)
                val (_, rootCompound) = reader.readRoot()
                reader.close()
                stream.close()
                val data = rootCompound.getCompound("Data")
                    ?: rootCompound

                val worldName = data.getString("LevelName", dir.name)
                val dataVersion = data.getInt("DataVersion", 0)
                val versionName = data.getCompound("Version")?.getString("Name", "unknown") ?: "unknown"
                // 1.19+ (dataVersion>=3120): seed is in Data/WorldGenSettings/seed
                // Older versions: Data/RandomSeed
                val seed = data.getCompound("WorldGenSettings")?.getLong("seed")
                    ?: data.getLong("RandomSeed", 0L)
                val spawnX = data.getInt("SpawnX", 0)
                val spawnZ = data.getInt("SpawnZ", 0)
                // The player's last position (Data.Player.Pos, a TAG_List of 3 doubles).
                // This is where they actually stand — guaranteed-generated terrain — so it
                // is the best place to center the map. Absent on server saves.
                var playerX: Double? = null
                var playerY: Double? = null
                var playerZ: Double? = null
                val playerPos = data.getCompound("Player")?.getList("Pos")
                if (playerPos != null && playerPos.value.size >= 3) {
                    val px = (playerPos.value[0] as? NbtReader.NbtTag.NbtDouble)?.value
                    val py = (playerPos.value[1] as? NbtReader.NbtTag.NbtDouble)?.value
                    val pz = (playerPos.value[2] as? NbtReader.NbtTag.NbtDouble)?.value
                    if (px != null && pz != null) {
                        playerX = px; playerY = py; playerZ = pz
                        Logger.i("Player last position: ($px, $pz)")
                    }
                }
                val lastPlayed = data.getLong("LastPlayed", 0L)

                val dimensions = mutableMapOf<DimensionType, String>()
                for (dim in DimensionType.entries) {
                    val regionDir = File(dir, dim.folderName)
                    val hasRegions = regionDir.exists() ||
                        (safAccess?.exists(dim.folderName, regionDir) == true)
                    if (hasRegions) {
                        dimensions[dim] = regionDir.absolutePath
                    }
                }

                Logger.i("World loaded: $worldName (v$versionName, dataVer=$dataVersion, seed=$seed)")

                WorldData(
                    worldName = worldName,
                    rootPath = dir.absolutePath,
                    dimensionPaths = dimensions,
                    gameVersion = versionName,
                    dataVersion = dataVersion,
                    seed = seed,
                    spawnX = spawnX,
                    spawnZ = spawnZ,
                    playerX = playerX,
                    playerY = playerY,
                    playerZ = playerZ,
                    lastPlayed = lastPlayed
                )
            } catch (e: Exception) {
                Logger.e("Failed to load world info from $worldPath", e)
                null
            }
        }
    }

    /**
     * Scan region files for actual chunk positions in a dimension.
     * Regions are independent, so they are scanned in parallel batches of 8
     * using zero-copy PFD access (no temp-file copy).
     */
    suspend fun scanChunks(dimensionPath: String): List<ChunkInfo> {
        return withFileIO {
            try {
                val regionDir = File(dimensionPath)
                val dim = DimensionType.fromFolder(dimensionPath) ?: DimensionType.OVERWORLD

                // Try SAF listing first, fall back to direct file
                val regionFiles: List<Pair<String, String>> = if (safAccess?.isAvailable == true) {
                    safAccess!!.listChildren(dim.folderName, regionDir).filter {
                        it.second.matches(RegionFileNameRegex)
                    }
                } else {
                    regionDir.listFiles { f -> f.name.matches(RegionFileNameRegex) }
                        ?.map { it.name to it.name } ?: emptyList()
                }

                if (regionFiles.isEmpty()) {
                    Logger.w("No region files found in $dimensionPath")
                    return@withFileIO emptyList()
                }

                val chunks = mutableListOf<ChunkInfo>()
                for (batch in regionFiles.chunked(8)) {
                    val batchResults: List<List<ChunkInfo>> = coroutineScope {
                        batch.map { (relPath, fileName) ->
                            async(Dispatchers.IO) {
                                scanOneRegion(relPath, fileName, dim, regionDir)
                            }
                        }.awaitAll()
                    }
                    chunks.addAll(batchResults.flatten())
                }
                Logger.i("Scanned ${chunks.size} chunks in dimension $dim")
                chunks
            } catch (e: Exception) {
                Logger.e("Failed to scan chunks", e)
                emptyList()
            }
        }
    }

    /** Scan a single region file, preferring zero-copy access (PFD, then direct
     *  file) with stream→temp-file copy as the last fallback. */
    private fun scanOneRegion(
        relPath: String,
        fileName: String,
        dim: DimensionType,
        regionDir: File
    ): List<ChunkInfo> {
        val match = REGION_FILE_REGEX.find(fileName) ?: return emptyList()
        val rx = match.groupValues[1].toInt()
        val rz = match.groupValues[2].toInt()
        val regionFile = File(regionDir, fileName)
        val chunks = mutableListOf<ChunkInfo>()

        val reader = when {
            safAccess != null -> {
                safAccess!!.openParcelFileDescriptor(relPath, regionFile)
                    ?.let { com.zaralynchisel.editioncore.AnvilReader.fromParcelFileDescriptor(it) }
                    ?: run {
                        val stream = safAccess!!.openInputStream(relPath, regionFile) ?: return emptyList()
                        com.zaralynchisel.editioncore.AnvilReader.fromStream(stream)
                    }
            }
            regionFile.exists() -> com.zaralynchisel.editioncore.AnvilReader(regionFile)
            else -> return emptyList()
        }

        if (!reader.open()) { reader.close(); return emptyList() }
        try {
            for (lx in 0 until 32) {
                for (lz in 0 until 32) {
                    val header = reader.readChunkHeader(lx, lz)
                    if (header != null && header.sectorOffset > 0 && header.sectorCount > 0) {
                        chunks.add(ChunkInfo(
                            x = (rx shl 5) + lx,
                            z = (rz shl 5) + lz,
                            dimension = dim,
                            timestamp = header.timestamp,
                            sectorCount = header.sectorCount
                        ))
                    }
                }
            }
        } finally {
            reader.close()
        }
        return chunks
    }

    /** Pre-open the region containing the given chunk so the first surface loads
     *  hit the LRU cache instead of paying the SAF PFD IPC latency. */
    suspend fun prewarmRegion(worldPath: String, chunkX: Int, chunkZ: Int, dimension: DimensionType) {
        withFileIO {
            readerMutex.withLock {
                val regionX = chunkX shr 5
                val regionZ = chunkZ shr 5
                val regionKey = (regionX.toLong() shl 32) or (regionZ.toLong() and 0xFFFFFFFFL)
                if (regionEntries.containsKey(regionKey)) return@withLock
                val dir = File(worldPath, dimension.folderName)
                val file = File(dir, "r.$regionX.$regionZ.mca")
                if (!file.exists()) return@withLock
                openRegionReader(worldPath, dimension, file, regionKey)
            }
        }
    }

    /**
     * Load surface MapColor data for a single chunk.
     * Returns the 256-entry IntArray (index = z*16 + x), or null on failure.
     */
    suspend fun loadChunkSurface(worldPath: String, chunkX: Int, chunkZ: Int, dimension: DimensionType): IntArray? {
        return try {
            // File I/O on the shared IO dispatcher (64 threads) — unlike the
            // 2-thread fileIO pool, many coroutines can reach the per-region locks
            // simultaneously, so different regions read in parallel.
            val data = withContext(Dispatchers.IO) {
                readChunkCompressed(worldPath, chunkX, chunkZ, dimension)
            } ?: return null

            // CPU-only decode on the caller's dispatcher — when called from the
            // loader's coroutineScope { async(IO) } this runs in parallel across
            // many IO threads, not bottlenecked on the 2-thread fileIO pool.
            val result = ChunkSurfaceReader.readSurfaceFlat(data, dimension)
            if (result != null) {
                val nonZeroCount = result.count { it != 0 }
                if (chunkX == 0 && chunkZ == 0) {
                    val sample = result.take(10).joinToString(",")
                    Logger.i("Surface loaded for origin chunk (0,0) dim=$dimension sample=[$sample] nonZero=$nonZeroCount/256")
                }
            } else {
                Logger.w("Surface load FAILED for chunk ($chunkX,$chunkZ) dim=$dimension")
            }
            result
        } catch (e: Exception) {
            Logger.e("Failed to load surface for ($chunkX, $chunkZ)", e)
            null
        }
    }

    /**
     * Load surface MapColor data + per-column absolute surface Y for a single chunk.
     * Used by the 3D player renderer. Returns null on failure.
     */
    suspend fun loadChunkSurfaceAndHeight(
        worldPath: String, chunkX: Int, chunkZ: Int, dimension: DimensionType
    ): com.zaralynchisel.renderengine.ChunkSurfaceReader.SurfaceData? {
        return try {
            val data = withContext(Dispatchers.IO) {
                readChunkCompressed(worldPath, chunkX, chunkZ, dimension)
            } ?: return null
            // CPU-only decode on caller's dispatcher (same pattern as loadChunkSurface)
            try {
                ChunkSurfaceReader.readSurfaceData(data, dimension)
            } catch (e: Exception) {
                Logger.e("Failed to decode surface+height for ($chunkX, $chunkZ)", e)
                null
            }
        } catch (e: Exception) {
            Logger.e("Failed to load surface+height for ($chunkX, $chunkZ)", e)
            null
        }
    }

    fun rememberWorld(worldPath: String) {
        prefs.addRecentWorld(worldPath)
    }

    fun getRecentWorlds(): List<String> = prefs.getRecentWorlds()

    fun forgetWorld(worldPath: String) {
        prefs.removeRecentWorld(worldPath)
    }

    /**
     * Resolve a SAF tree URI to an actual file path (if possible).
     */
    fun resolveSafUri(uri: Uri): String? {
        return try {
            val docId = documentsContractCompat.getTreeDocumentId(uri)
            Logger.d("SAF docId: $docId")
            if (docId.startsWith("primary:")) {
                val path = docId.removePrefix("primary:")
                val resolved = "/storage/emulated/0/$path"
                Logger.d("Resolved SAF to: $resolved")
                resolved
            } else {
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    val resolved = "/storage/${parts[0]}/${parts[1]}"
                    Logger.d("Resolved SAF to: $resolved")
                    resolved
                } else null
            }
        } catch (e: Exception) {
            Logger.e("Failed to resolve SAF URI: $uri", e)
            null
        }
    }

    sealed class ValidationResult {
        data class Valid(val path: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    companion object {
        private const val MAX_CACHED_REGIONS = 16
        private val RegionFileNameRegex = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.mca")
        private val REGION_FILE_REGEX = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.mca")

        private val documentsContractCompat = object {
            fun getTreeDocumentId(uri: Uri): String =
                android.provider.DocumentsContract.getTreeDocumentId(uri)
        }
    }
}