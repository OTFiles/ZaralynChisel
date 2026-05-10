package com.zaralynchisel.fileaccess

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.zaralynchisel.editioncore.DimensionType
import com.zaralynchisel.editioncore.WorldData
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.PreferenceManager
import com.zaralynchisel.utils.withFileIO
import java.io.File

/**
 * Handles world selection and validation.
 * Users navigate to their world folder manually via SAF or built-in file browser.
 * No paths are pre-scanned — the user always chooses.
 */
class WorldSelector(private val context: Context) {

    private val prefs = PreferenceManager(context)

    /**
     * Validate that a given path is a valid Minecraft world.
     * Checks for level.dat and region folders.
     */
    suspend fun validateWorld(worldPath: String): ValidationResult {
        return withFileIO {
            val dir = File(worldPath)
            if (!dir.exists() || !dir.isDirectory) {
                return@withFileIO ValidationResult.Invalid("Path does not exist or is not a directory")
            }

            val levelDat = File(dir, "level.dat")
            if (!levelDat.exists()) {
                return@withFileIO ValidationResult.Invalid("No level.dat found — not a Minecraft world")
            }

            // Check for at least one region folder
            val hasRegion = DimensionType.entries.any { dim ->
                File(dir, dim.folderName).exists()
            }

            if (!hasRegion) {
                return@withFileIO ValidationResult.Invalid("No region files found — world may be empty or corrupted")
            }

            ValidationResult.Valid(worldPath)
        }
    }

    /**
     * Load world metadata from level.dat.
     */
    suspend fun loadWorldInfo(worldPath: String): WorldData? {
        return withFileIO {
            try {
                val dir = File(worldPath)
                val levelDat = File(dir, "level.dat")

                if (!levelDat.exists()) return@withFileIO null

                // TODO: Parse level.dat with Hephaistos to extract:
                // - LevelName, DataVersion, RandomSeed, SpawnX/SpawnZ, LastPlayed
                // For now, return basic info from folder name
                val dimensions = mutableMapOf<DimensionType, String>()
                for (dim in DimensionType.entries) {
                    val regionDir = File(dir, dim.folderName)
                    if (regionDir.exists()) {
                        dimensions[dim] = regionDir.absolutePath
                    }
                }

                WorldData(
                    worldName = dir.name,
                    rootPath = dir.absolutePath,
                    dimensionPaths = dimensions,
                    gameVersion = "unknown",
                    dataVersion = 0
                )
            } catch (e: Exception) {
                Logger.e("Failed to load world info from $worldPath", e)
                null
            }
        }
    }

    /**
     * Register a world as recently opened.
     */
    fun rememberWorld(worldPath: String) {
        prefs.addRecentWorld(worldPath)
    }

    /**
     * Get list of recently opened worlds.
     */
    fun getRecentWorlds(): List<String> = prefs.getRecentWorlds()

    /**
     * Remove a world from recent list.
     */
    fun forgetWorld(worldPath: String) {
        prefs.removeRecentWorld(worldPath)
    }

    /**
     * Resolve a SAF tree URI to an actual file path (if possible).
     */
    fun resolveSafUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // SAF URIs on Android typically look like:
            // content://com.android.externalstorage.documents/tree/primary%3Apath
            if (docId.startsWith("primary:")) {
                val path = docId.removePrefix("primary:")
                "/storage/emulated/0/$path"
            } else {
                // External SD card or other storage
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    "/storage/${parts[0]}/${parts[1]}"
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
}