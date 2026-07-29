package com.zaralynchisel.fileaccess

import android.content.Context
import android.net.Uri
import com.zaralynchisel.editioncore.*
import com.zaralynchisel.renderengine.ChunkSurfaceReader
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.PreferenceManager
import com.zaralynchisel.utils.withFileIO
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
    // ponytail: cache last-opened region reader, same-region chunks all need it
    private var cachedReader: com.zaralynchisel.editioncore.AnvilReader? = null
    private var cachedRegionIdx: Long = -1L

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
                var playerZ: Double? = null
                val playerPos = data.getCompound("Player")?.getList("Pos")
                if (playerPos != null && playerPos.value.size >= 3) {
                    val px = (playerPos.value[0] as? NbtReader.NbtTag.NbtDouble)?.value
                    val pz = (playerPos.value[2] as? NbtReader.NbtTag.NbtDouble)?.value
                    if (px != null && pz != null) {
                        playerX = px; playerZ = pz
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
     */
    suspend fun scanChunks(dimensionPath: String): List<ChunkInfo> {
        return withFileIO {
            val chunks = mutableListOf<ChunkInfo>()
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
                    return@withFileIO chunks
                }

                for ((relPath, fileName) in regionFiles) {
                    val match = REGION_FILE_REGEX.find(fileName) ?: continue
                    val rx = match.groupValues[1].toInt()
                    val rz = match.groupValues[2].toInt()

                    Logger.d("Scanning region: $fileName")

                    val regionFile = File(regionDir, fileName)
                    val stream = if (safAccess != null) {
                        safAccess!!.openInputStream(relPath, regionFile)
                    } else if (regionFile.exists()) {
                        BufferedInputStream(FileInputStream(regionFile))
                    } else {
                        null
                    }

                    if (stream == null) {
                        Logger.w("Cannot open region file: $fileName")
                        continue
                    }

                    val reader = AnvilReader.fromStream(stream)
                    if (reader.open()) {
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
                    } else {
                        Logger.w("Failed to open region file: $fileName")
                    }
                }
                Logger.i("Scanned ${chunks.size} chunks in dimension $dim")
            } catch (e: Exception) {
                Logger.e("Failed to scan chunks", e)
            }
            chunks
        }
    }

    /**
     * Load surface MapColor data for a single chunk.
     * Returns the 256-entry IntArray (index = z*16 + x), or null on failure.
     */
    suspend fun loadChunkSurface(worldPath: String, chunkX: Int, chunkZ: Int, dimension: DimensionType): IntArray? {
        return withFileIO {
            try {
                // dimension.folderName is already the relative path, e.g. "region" or "DIM-1/region"
                val regionDir = File(worldPath, dimension.folderName)
                val regionX = chunkX shr 5
                val regionZ = chunkZ shr 5
                val regionFile = File(regionDir, "r.$regionX.$regionZ.mca")
                if (!regionFile.exists()) return@withFileIO null

                val regionKey = (regionX.toLong() shl 32) or (regionZ.toLong() and 0xFFFFFFFFL)
                // ponytail: reuse last region reader, skip reopen+recopy
                if (cachedRegionIdx != regionKey || cachedReader == null) {
                    cachedReader?.close(); cachedReader = null

                    val stream = if (safAccess != null) {
                        val relPath = "${dimension.folderName}/r.$regionX.$regionZ.mca"
                        safAccess!!.openInputStream(relPath, regionFile)
                    } else if (regionFile.exists()) {
                        java.io.BufferedInputStream(java.io.FileInputStream(regionFile))
                    } else null

                    if (stream == null) return@withFileIO null

                    cachedReader = com.zaralynchisel.editioncore.AnvilReader.fromStream(stream)
                    cachedReader!!.open()
                    cachedRegionIdx = regionKey
                }
                val reader = cachedReader!!
                val lx = chunkX and 31
                val lz = chunkZ and 31
                val result = reader.readChunkSurface(lx, lz, dimension)
                // ponytail: keep reader open for next same-region chunk
                if (result != null) {
                    val nonZeroCount = result.count { it != 0 }
                    if (chunkX == 0 && chunkZ == 0) {
                        // Detailed log for origin chunk only
                        val sample = result.take(10).joinToString(",")
                        Logger.i("Surface loaded for origin chunk (0,0) dim=$dimension region=r.$regionX.$regionZ.mca sample=[$sample] nonZero=$nonZeroCount/256")
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
    }

    /**
     * Load surface MapColor data + per-column absolute surface Y for a single chunk.
     * Used by the 3D player renderer. Returns null on failure.
     */
    suspend fun loadChunkSurfaceAndHeight(
        worldPath: String, chunkX: Int, chunkZ: Int, dimension: DimensionType
    ): com.zaralynchisel.renderengine.ChunkSurfaceReader.SurfaceData? {
        return withFileIO {
            try {
                val regionDir = File(worldPath, dimension.folderName)
                val regionX = chunkX shr 5
                val regionZ = chunkZ shr 5
                val regionFile = File(regionDir, "r.$regionX.$regionZ.mca")
                if (!regionFile.exists()) return@withFileIO null

                val regionKey = (regionX.toLong() shl 32) or (regionZ.toLong() and 0xFFFFFFFFL)
                if (cachedRegionIdx != regionKey || cachedReader == null) {
                    cachedReader?.close(); cachedReader = null
                    val stream = if (safAccess != null) {
                        val relPath = "${dimension.folderName}/r.$regionX.$regionZ.mca"
                        safAccess!!.openInputStream(relPath, regionFile)
                    } else if (regionFile.exists()) {
                        java.io.BufferedInputStream(java.io.FileInputStream(regionFile))
                    } else null
                    if (stream == null) return@withFileIO null
                    cachedReader = com.zaralynchisel.editioncore.AnvilReader.fromStream(stream)
                    cachedReader!!.open()
                    cachedRegionIdx = regionKey
                }
                val reader = cachedReader!!
                val data = reader.readChunkData(chunkX and 31, chunkZ and 31) ?: return@withFileIO null
                ChunkSurfaceReader.readSurfaceData(data, dimension)
            } catch (e: Exception) {
                Logger.e("Failed to load surface+height for ($chunkX, $chunkZ)", e)
                null
            }
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
        private val RegionFileNameRegex = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.mca")
        private val REGION_FILE_REGEX = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.mca")

        private val documentsContractCompat = object {
            fun getTreeDocumentId(uri: Uri): String =
                android.provider.DocumentsContract.getTreeDocumentId(uri)
        }
    }
}