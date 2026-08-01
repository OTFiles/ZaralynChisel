package com.zaralynchisel.fileaccess

import android.content.Context
import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.PreferenceManager
import java.io.File

/**
 * Central texture resolution service.
 * Implements the priority chain defined in the design doc:
 * 1. Local assets index → objects/
 * 2. Mod JAR textures
 * 3. Network download (cached)
 * 4. Checkerboard placeholder (if all fail)
 */
class TextureResolver(private val context: Context) {

    private val prefs = PreferenceManager(context)
    private val assetsExtractor = AssetsExtractor(context)
    private val modsExtractor = ModsJarExtractor()
    private lateinit var networkFetcher: NetworkFetcher

    private var minecraftDir: String? = null
    private var modsDir: String? = null
    private var version: String = "unknown"
    private var isInitialized = false

    /**
     * Initialize the texture resolver with a world path.
     * This locates the .minecraft directory and mods folder.
     */
    suspend fun initialize(worldPath: String) {
        minecraftDir = assetsExtractor.locateMinecraftDir(worldPath)
        modsDir = minecraftDir?.let { File(it, "mods").absolutePath }

        val cacheDir = File(context.cacheDir, "texture_cache")
        networkFetcher = NetworkFetcher(cacheDir)

        isInitialized = true
        Logger.d("TextureResolver initialized. MC dir: $minecraftDir, Mods dir: $modsDir")
    }

    /**
     * Set the Minecraft version for asset index lookup.
     */
    fun setVersion(version: String) {
        this.version = version
    }

    /**
     * Resolve a block texture by its resource path.
     * Returns null only if ALL sources fail.
     */
    suspend fun resolveBlockTexture(blockResourcePath: String): ByteArray? {
        if (!isInitialized) {
            Logger.w("TextureResolver not initialized")
            return null
        }

        // Priority 1: Local assets index
        if (minecraftDir != null) {
            try {
                val fromAssets = assetsExtractor.getBlockTexture(
                    minecraftDir!!, version, blockResourcePath
                )
                if (fromAssets != null) {
                    Logger.d("Texture resolved from assets: $blockResourcePath")
                    return fromAssets
                }
            } catch (e: Exception) {
                Logger.w("Assets lookup failed for $blockResourcePath: ${e.message}")
            }
        }

        // Priority 2: the game's client jar (vanilla textures; some launchers
        // like FCL have no Mojang-style asset index). Faster and more reliable
        // than scanning mod jars, so it comes before the mods.
        if (minecraftDir != null) {
            try {
                val fromJar = assetsExtractor.getTextureFromVersionJar(minecraftDir!!, version, blockResourcePath)
                if (fromJar != null) {
                    Logger.d("Texture resolved from version jar: $blockResourcePath")
                    return fromJar
                }
            } catch (e: Exception) {
                Logger.w("Version jar lookup failed for $blockResourcePath: ${e.message}")
            }
        }

        // Priority 3: Mod JARs
        if (modsDir != null) {
            try {
                val namespace = if (blockResourcePath.contains(":")) {
                    blockResourcePath.substringBefore(":")
                } else {
                    "minecraft"
                }
                val path = blockResourcePath.substringAfter(":")
                val textureName = path.substringAfterLast("/")

                val fromMod = modsExtractor.getTextureFromMods(modsDir!!, namespace, textureName)
                if (fromMod != null) {
                    Logger.d("Texture resolved from mod: $blockResourcePath")
                    return fromMod
                }
            } catch (e: Exception) {
                Logger.w("Mod lookup failed for $blockResourcePath: ${e.message}")
            }
        }

        // Priority 4: Network download (if we have the hash from the index)
        if (minecraftDir != null) {
            try {
                val index = assetsExtractor.loadAssetIndex(minecraftDir!!, version)
                if (index != null) {
                    // Asset index keys use the "<namespace>: <path>" form, e.g.
                    // "minecraft: textures/block/stone.png" (note the space).
                    val namespace = if (blockResourcePath.contains(":")) {
                        blockResourcePath.substringBefore(":")
                    } else {
                        "minecraft"
                    }
                    val path = blockResourcePath.substringAfter(":")
                    val assetPath = "$namespace: textures/$path.png"
                    val assetObject = index[assetPath]
                    if (assetObject != null) {
                        val fromNetwork = networkFetcher.downloadTexture(
                            assetObject.hash,
                            prefs.textureMirrorUrl
                        )
                        if (fromNetwork != null) {
                            Logger.d("Texture resolved from network: $blockResourcePath")
                            return fromNetwork
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w("Network download failed for $blockResourcePath: ${e.message}")
            }
        }

        // Priority 4: All sources failed
        Logger.w("All texture sources failed for: $blockResourcePath")
        return null
    }

    /**
     * Check if Player Mode can be enabled (at least some textures available).
     */
    suspend fun canEnablePlayerMode(): Boolean {
        // Try to resolve a common block texture as a test
        val testTexture = resolveBlockTexture("minecraft:block/stone")
        return testTexture != null
    }

    /**
     * Get the network fetcher for cache management.
     */
    fun getNetworkFetcher(): NetworkFetcher? =
        if (::networkFetcher.isInitialized) networkFetcher else null

    /**
     * Clear all caches.
     */
    fun clearCaches() {
        getNetworkFetcher()?.clearCache()
    }

    /** Release the cached version-jar handle (call when discarding this resolver). */
    fun close() {
        assetsExtractor.close()
    }
}