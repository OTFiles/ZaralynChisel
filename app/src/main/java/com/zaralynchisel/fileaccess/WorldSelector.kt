package com.zaralynchisel.fileaccess

import android.content.Context
import android.net.Uri
import com.zaralynchisel.editioncore.*
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.PreferenceManager
import com.zaralynchisel.utils.withFileIO
import java.io.File
import java.io.FileInputStream

/**
 * Handles world selection and validation.
 * Supports both direct file paths and SAF document trees.
 */
class WorldSelector(private val context: Context) {

    private val prefs = PreferenceManager(context)

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
            if (!levelDat.exists()) {
                Logger.w("No level.dat found at $worldPath")
                return@withFileIO ValidationResult.Invalid("未找到 level.dat — 不是 Minecraft 存档")
            }
            val hasRegion = DimensionType.entries.any { dim ->
                File(dir, dim.folderName).exists()
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
                if (!levelDat.exists()) return@withFileIO null

                Logger.i("Reading level.dat from $worldPath")

                val reader = FileInputStream(levelDat).use { stream ->
                    NbtReader(stream)
                }
                val (_, rootCompound) = reader.readRoot()
                val data = rootCompound.getCompound("Data")
                    ?: rootCompound

                val worldName = data.getString("LevelName", dir.name)
                val dataVersion = data.getInt("DataVersion", 0)
                val versionName = data.getCompound("Version")?.getString("Name", "unknown") ?: "unknown"
                val seed = data.getLong("RandomSeed", 0L)
                val spawnX = data.getInt("SpawnX", 0)
                val spawnZ = data.getInt("SpawnZ", 0)
                val lastPlayed = data.getLong("LastPlayed", 0L)

                val dimensions = mutableMapOf<DimensionType, String>()
                for (dim in DimensionType.entries) {
                    val regionDir = File(dir, dim.folderName)
                    if (regionDir.exists()) {
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
                if (!regionDir.exists()) {
                    Logger.w("Region directory not found: $dimensionPath")
                    return@withFileIO chunks
                }
                val regionFiles = regionDir.listFiles { f ->
                    f.name.matches(RegionFileNameRegex)
                } ?: return@withFileIO chunks

                val dim = DimensionType.fromFolder(dimensionPath) ?: DimensionType.OVERWORLD

                for (regionFile in regionFiles) {
                    val match = REGION_FILE_REGEX.find(regionFile.name) ?: continue
                    val rx = match.groupValues[1].toInt()
                    val rz = match.groupValues[2].toInt()

                    Logger.d("Scanning region: ${regionFile.name}")

                    val reader = AnvilReader(regionFile)
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
                                            timestamp = header.timestamp
                                        ))
                                    }
                                }
                            }
                        } finally {
                            reader.close()
                        }
                    } else {
                        Logger.w("Failed to open region file: ${regionFile.name}")
                    }
                }
                Logger.i("Scanned ${chunks.size} chunks in dimension $dim")
            } catch (e: Exception) {
                Logger.e("Failed to scan chunks", e)
            }
            chunks
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

        // Wrap DocumentsContract for testability on non-Android platforms
        private val documentsContractCompat = object {
            fun getTreeDocumentId(uri: Uri): String =
                android.provider.DocumentsContract.getTreeDocumentId(uri)
        }
    }
}