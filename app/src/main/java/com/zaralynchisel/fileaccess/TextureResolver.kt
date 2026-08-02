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

    /** Asset index is ~MBs of JSON — parse it once and reuse. */
    private var cachedIndex: Map<String, AssetsExtractor.AssetObject>? = null

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
     * Resolve a block texture by its resource path ("minecraft:block/stone").
     * Returns null only if ALL sources fail.
     *
     * The path may not exist verbatim (stairs/slabs reuse the base block's
     * texture, crafting_table has only _top/_side/_front, wood reuses log
     * textures...), so each source tries a chain of candidate paths.
     */
    suspend fun resolveBlockTexture(blockResourcePath: String): ByteArray? {
        if (!isInitialized) {
            Logger.w("TextureResolver not initialized")
            return null
        }

        val candidates = textureCandidates(blockResourcePath)

        // Priority 1: Local assets index (loaded once, reused across sources)
        if (minecraftDir != null) {
            val index = cachedIndex ?: assetsExtractor.loadAssetIndex(minecraftDir!!, version)
            if (index != null) {
                cachedIndex = index
                for (c in candidates) {
                    val assetObject = index[toAssetKey(c)]
                    if (assetObject != null) {
                        val fromAssets = assetsExtractor.getTextureByHash(minecraftDir!!, assetObject.hash)
                        if (fromAssets != null) {
                            Logger.d("Texture resolved from assets: $blockResourcePath")
                            return fromAssets
                        }
                    }
                }
            }
        }

        // Priority 2: the game's client jar (vanilla textures; some launchers
        // like FCL have no Mojang-style asset index).
        if (minecraftDir != null) {
            for (c in candidates) {
                try {
                    val fromJar = assetsExtractor.getTextureFromVersionJar(minecraftDir!!, version, c)
                    if (fromJar != null) {
                        Logger.d("Texture resolved from version jar: $blockResourcePath")
                        return fromJar
                    }
                } catch (e: Exception) {
                    Logger.w("Version jar lookup failed for $c: ${e.message}")
                }
            }
        }

        // Priority 3: Mod JARs
        if (modsDir != null) {
            for (c in candidates) {
                try {
                    val namespace = if (c.contains(":")) c.substringBefore(":") else "minecraft"
                    val path = c.substringAfter(":")
                    val textureName = path.substringAfterLast("/")

                    val fromMod = modsExtractor.getTextureFromMods(modsDir!!, namespace, textureName)
                    if (fromMod != null) {
                        Logger.d("Texture resolved from mod: $blockResourcePath")
                        return fromMod
                    }
                } catch (e: Exception) {
                    Logger.w("Mod lookup failed for $c: ${e.message}")
                }
            }
        }

        // Priority 4: Network download (if we have the hash from the index)
        if (minecraftDir != null) {
            val index = cachedIndex ?: assetsExtractor.loadAssetIndex(minecraftDir!!, version)
            if (index != null) {
                cachedIndex = index
                for (c in candidates) {
                    val assetObject = index[toAssetKey(c)]
                    if (assetObject != null) {
                        val fromNetwork = networkFetcher.downloadTexture(assetObject.hash, prefs.textureMirrorUrl)
                        if (fromNetwork != null) {
                            Logger.d("Texture resolved from network: $blockResourcePath")
                            return fromNetwork
                        }
                    }
                }
            }
        }

        // All sources failed
        Logger.w("All texture sources failed for: $blockResourcePath")
        return null
    }

    /** Asset index keys use the "<namespace>: <path>" form (note the space). */
    private fun toAssetKey(resourcePath: String): String {
        val namespace = if (resourcePath.contains(":")) resourcePath.substringBefore(":") else "minecraft"
        val path = resourcePath.substringAfter(":")
        return "$namespace: textures/$path.png"
    }

    /**
     * Candidate texture paths for a block, most specific first. Handles blocks
     * whose textures are named differently from the block id (crafting_table →
     * crafting_table_top, stone_brick_stairs → stone_bricks, cherry_wood →
     * cherry_log, glass_pane → glass_pane_top, tall_seagrass → _top ...) plus
     * generic _top/_side/_front/_still variants as a last resort.
     */
    private fun textureCandidates(block: String): List<String> {
        val out = LinkedHashSet<String>()
        val ns = if (block.contains(":")) block.substringBefore(":") else "minecraft"
        // "minecraft:block/stone" → "stone" (strip namespace AND the block/ prefix)
        val id = block.substringAfter(":").removePrefix("block/")
        fun p(name: String) = "$ns:block/$name"

        // Direct special cases (verified against the 1.21 client jar).
        when (id) {
            "glass_pane" -> return listOf(p("glass_pane_top"), p("glass_pane"))
            "crafting_table" -> return listOf(p("crafting_table_top"), p("crafting_table_front"), p("crafting_table_side"))
            "tall_seagrass" -> return listOf(p("tall_seagrass_top"), p("tall_seagrass_bottom"))
            "bamboo" -> return listOf(p("bamboo_stalk"), p("bamboo_block"))
        }
        // Blocks that reuse the base block's texture (stairs/slabs/walls/...).
        for (suffix in listOf(
            "_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_button",
            "_pressure_plate", "_door", "_trapdoor", "_torch", "_rail", "_sign",
            "_banner", "_carpet", "_sapling", "_flower_pot", "_bed", "_chest"
        )) {
            if (id.endsWith(suffix)) {
                val base = id.removeSuffix(suffix)
                out.add(p(base))
                out.add(p(base + "s"))   // stone_brick_stairs → stone_bricks
                out.add(p(base + "_top"))
                // Stairs/slabs are textured with the matching planks tile.
                if (suffix == "_stairs" || suffix == "_slab") {
                    out.add(p(base + "_planks"))
                }
                break
            }
        }
        // Bark blocks reuse the log texture (cherry_wood → cherry_log).
        if (id.endsWith("_wood") && !id.endsWith("_wooden_")) {
            out.add(p(id.removeSuffix("_wood") + "_log"))
            out.add(p(id.removeSuffix("_wood") + "_log_top"))
        }
        // Generic variants.
        out.add(p(id + "_top"))
        out.add(p(id + "_side"))
        out.add(p(id + "_front"))
        out.add(p(id + "_still"))
        out.add(p(id))
        return out.toList()
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