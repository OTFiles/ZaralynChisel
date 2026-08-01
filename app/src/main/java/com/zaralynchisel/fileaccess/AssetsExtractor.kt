package com.zaralynchisel.fileaccess

import android.content.Context
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.withFileIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Extracts Minecraft textures from the local .minecraft/assets directory.
 *
 * Priority:
 * 1. .minecraft/assets/indexes/<version>.json (object mapping)
 * 2. .minecraft/assets/objects/ (actual files by hash)
 */
class AssetsExtractor(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Result of a texture lookup.
     */
    data class TextureResult(
        val data: ByteArray,
        val source: TextureSource
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TextureResult) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    enum class TextureSource {
        ASSETS_INDEX,
        MOD_JAR,
        NETWORK_DOWNLOAD,
        CACHE
    }

    /**
     * Locate the .minecraft directory by searching near the world folder.
     * Typical layout: .minecraft/ and .minecraft/saves/ are siblings.
     */
    suspend fun locateMinecraftDir(worldPath: String): String? {
        return withFileIO {
            val worldDir = File(worldPath)

            // Try common parent structures
            val candidates = listOf(
                worldDir.parentFile?.let { File(it, ".minecraft") },
                worldDir.parentFile?.parentFile?.let { File(it, ".minecraft") },
                File("/storage/emulated/0/games/com.mojang"),
                File("/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang"),
                File("/data/data/com.mojang.minecraftpe/files/games/com.mojang")
            )

            for (candidate in candidates) {
                if (candidate != null && candidate.exists() && File(candidate, "assets").exists()) {
                    Logger.d("Found .minecraft at: ${candidate.absolutePath}")
                    return@withFileIO candidate.absolutePath
                }
            }

            Logger.w("Could not locate .minecraft directory")
            null
        }
    }

    /**
     * Load the asset index for a specific Minecraft version.
     */
    suspend fun loadAssetIndex(minecraftDir: String, version: String): Map<String, AssetObject>? {
        return withFileIO {
            try {
                // The launcher may name the index after the full version id
                // ("1.21.1-NeoForge") while the file itself is the base game's
                // ("1.21.1"), so fall back to the prefix before the first '-'.
                val candidates = listOf(version, version.substringBefore("-"))
                    .distinct()
                    .map { File(minecraftDir, "assets/indexes/$it.json") }
                val indexFile = candidates.firstOrNull { it.exists() }
                    ?: run {
                        Logger.w("Asset index not found for version '$version' in ${candidates.joinToString()}")
                        return@withFileIO null
                    }

                val content = indexFile.readText()
                val root = json.parseToJsonElement(content).jsonObject
                val objects = root["objects"]?.jsonObject ?: return@withFileIO null

                val result = mutableMapOf<String, AssetObject>()
                for ((key, value) in objects) {
                    val obj = value.jsonObject
                    val hash = obj["hash"]?.toString()?.trim('"') ?: continue
                    val size = obj["size"]?.toString()?.toLongOrNull() ?: 0L
                    result[key] = AssetObject(hash = hash, size = size)
                }

                Logger.d("Loaded asset index with ${result.size} entries for version $version")
                result
            } catch (e: Exception) {
                Logger.e("Failed to load asset index for $version", e)
                null
            }
        }
    }

    /**
     * Get a texture file from the objects store by its hash.
     */
    suspend fun getTextureByHash(minecraftDir: String, hash: String): ByteArray? {
        return withFileIO {
            try {
                val prefix = hash.substring(0, 2)
                val textureFile = File(minecraftDir, "assets/objects/$prefix/$hash")
                if (textureFile.exists()) {
                    textureFile.readBytes()
                } else {
                    Logger.w("Texture file not found: $prefix/$hash")
                    null
                }
            } catch (e: Exception) {
                Logger.e("Failed to read texture $hash", e)
                null
            }
        }
    }

    /**
     * Get a block texture by its resource path (e.g. "minecraft:block/stone").
     */
    suspend fun getBlockTexture(
        minecraftDir: String,
        version: String,
        blockResourcePath: String
    ): ByteArray? {
        val index = loadAssetIndex(minecraftDir, version) ?: return null

        // Convert "minecraft:block/stone" to "minecraft: textures/block/stone.png"
        val namespace = if (blockResourcePath.contains(":")) {
            blockResourcePath.substringBefore(":")
        } else {
            "minecraft"
        }
        val path = blockResourcePath.substringAfter(":")
        val assetPath = "$namespace: textures/$path.png"

        val assetObject = index[assetPath] ?: return null
        return getTextureByHash(minecraftDir, assetObject.hash)
    }

    /**
     * Compute SHA-1 hash of a file (used for asset verification).
     */
    fun sha1Hash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    data class AssetObject(
        val hash: String,
        val size: Long
    )
}