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

    /** Cached handle to the game's client jar (textures live inside it, like
     *  BlueMap's resource pack). Opened once, closed via [close]. */
    @Volatile
    private var versionJar: java.util.zip.ZipFile? = null

    /** The world path passed to [locateMinecraftDir]; its versions/<dir> ancestor
     *  identifies the exact launcher/loader directory (1.21.1-NeoForge, vanilla...). */
    private var worldPath: String? = null

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
            this@AssetsExtractor.worldPath = worldPath
            // Walk up from the world directory: the .minecraft folder can be any
            // number of levels up (e.g. FCL: saves/<world> under versions/<ver>),
            // so keep climbing until a directory that owns assets/ is found.
            // Never walk past a .minecraft folder itself.
            var cur: File? = File(worldPath)
            while (cur != null) {
                if (cur.name == ".minecraft") {
                    // Stop here — never inspect anything above the game directory.
                    val ok = File(cur, "assets").exists() || File(cur, "versions").exists()
                    if (ok) Logger.d("Found .minecraft at: ${cur.absolutePath}")
                    else Logger.w("Found .minecraft without assets/versions: ${cur.absolutePath}")
                    return@withFileIO if (ok) cur.absolutePath else null
                }
                val minecraft = File(cur, ".minecraft")
                if (minecraft.exists() &&
                    (File(minecraft, "assets").exists() || File(minecraft, "versions").exists())) {
                    Logger.d("Found .minecraft at: ${minecraft.absolutePath}")
                    return@withFileIO minecraft.absolutePath
                }
                cur = cur.parentFile
            }

            // Fallbacks for launchers with a fixed install location.
            val candidates = listOf(
                File("/storage/emulated/0/games/com.mojang"),
                File("/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang"),
                File("/data/data/com.mojang.minecraftpe/files/games/com.mojang")
            )
            for (candidate in candidates) {
                if (candidate.exists() && File(candidate, "assets").exists()) {
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
     * Read a texture straight from the game's client jar
     * (versions/<dir>/<dir>.jar → assets/<ns>/textures/<path>.png). This is the
     * same source BlueMap uses for the vanilla resource pack; some launchers
     * (e.g. FCL) don't ship a Mojang-style asset index with block textures.
     */
    suspend fun getTextureFromVersionJar(minecraftDir: String, version: String, blockResourcePath: String): ByteArray? {
        return withFileIO {
            try {
                val jar = openVersionJar(minecraftDir, version) ?: return@withFileIO null
                val namespace = if (blockResourcePath.contains(":")) blockResourcePath.substringBefore(":") else "minecraft"
                val path = blockResourcePath.substringAfter(":")
                val entryPath = "assets/$namespace/textures/$path.png"
                val entry = jar.getEntry(entryPath)
                if (entry == null) return@withFileIO null
                jar.getInputStream(entry).use { it.readBytes() }
            } catch (e: Exception) {
                Logger.w("Version jar lookup failed for $blockResourcePath: ${e.message}")
                null
            }
        }
    }

    private fun openVersionJar(minecraftDir: String, version: String): java.util.zip.ZipFile? {
        val cached = versionJar
        if (cached != null) return cached
        synchronized(this) {
            versionJar?.let { return it }
            val versionsDir = File(minecraftDir, "versions")
            val dirs = versionsDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

            // The save lives under versions/<dir>/saves/<world>, so its parent
            // directory name identifies the exact loader install (e.g.
            // 1.21.1-NeoForge). Prefer that, then loader-flavoured ids, then
            // plain version ids, then anything.
            val worldVersionDir = worldPath?.let { File(it).parentFile?.parentFile?.name }
            val knownLoaders = listOf("neoforge", "forge", "fabric", "quilt")
            val scored = dirs.map { d ->
                val score = when {
                    d.name == worldVersionDir -> 1000
                    d.name == version -> 30
                    d.name.startsWith(version) &&
                        knownLoaders.any { d.name.lowercase().contains(it) } -> 20
                    d.name.startsWith(version) || version.startsWith(d.name) -> 10
                    else -> 0
                }
                d to score
            }.sortedByDescending { it.second }
            val preferred = scored.firstOrNull { it.second > 0 }?.first ?: dirs.firstOrNull()
            if (preferred == null) {
                Logger.w("No version directories under ${versionsDir.absolutePath}")
                return null
            }
            val jarFile = File(preferred, "${preferred.name}.jar")
            if (!jarFile.exists()) {
                Logger.w("No client jar at ${jarFile.absolutePath}")
                return null
            }
            val jar = try {
                java.util.zip.ZipFile(jarFile)
            } catch (e: Exception) {
                Logger.w("Failed to open ${jarFile.absolutePath}: ${e.message}")
                null
            } ?: return null
            Logger.d("Opened version jar for textures: ${jarFile.absolutePath}")
            versionJar = jar
            return jar
        }
    }

    /** Close the cached version jar (call when the texture resolver is discarded). */
    fun close() {
        synchronized(this) {
            try {
                versionJar?.close()
            } catch (_: Exception) { }
            versionJar = null
        }
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