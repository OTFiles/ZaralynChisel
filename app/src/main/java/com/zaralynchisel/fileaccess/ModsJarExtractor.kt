package com.zaralynchisel.fileaccess

import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.withFileIO
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Extracts textures from mod JAR files (Forge, Fabric, NeoForge).
 * Searches for assets/<modid>/textures/block/ inside each JAR.
 */
class ModsJarExtractor {

    /**
     * Result of extracting a texture from a mod JAR.
     */
    data class ModTexture(
        val modId: String,
        val texturePath: String,
        val data: ByteArray
    )

    /**
     * Scan a mods directory and extract all block textures.
     * @param modsDir Path to the mods folder (e.g. .minecraft/mods/)
     * @return List of extracted textures with their mod IDs
     */
    suspend fun extractAllBlockTextures(modsDir: String): List<ModTexture> {
        return withFileIO {
            val textures = mutableListOf<ModTexture>()
            val dir = File(modsDir)

            if (!dir.exists() || !dir.isDirectory) {
                Logger.w("Mods directory not found: $modsDir")
                return@withFileIO emptyList()
            }

            val jarFiles = dir.listFiles { f -> f.extension in setOf("jar", "jar.disabled") }
            if (jarFiles.isNullOrEmpty()) {
                Logger.d("No mod JARs found in $modsDir")
                return@withFileIO emptyList()
            }

            for (jarFile in jarFiles) {
                try {
                    val extracted = extractFromJar(jarFile)
                    textures.addAll(extracted)
                    Logger.d("Extracted ${extracted.size} textures from ${jarFile.name}")
                } catch (e: Exception) {
                    Logger.w("Failed to extract textures from ${jarFile.name}: ${e.message}")
                }
            }

            textures
        }
    }

    /**
     * Extract block textures from a single JAR file.
     */
    private fun extractFromJar(jarFile: File): List<ModTexture> {
        val textures = mutableListOf<ModTexture>()
        val zip = ZipFile(jarFile)

        try {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                // Match: assets/<modid>/textures/block/<name>.png
                val match = BLOCK_TEXTURE_REGEX.find(name)
                if (match != null) {
                    val modId = match.groupValues[1]
                    val texturePath = match.groupValues[2]

                    if (!entry.isDirectory) {
                        val data = zip.getInputStream(entry).readBytes()
                        textures.add(ModTexture(modId, texturePath, data))
                    }
                }
            }
        } finally {
            zip.close()
        }

        return textures
    }

    /**
     * Check if a specific mod provides a given block texture.
     */
    suspend fun getTextureFromMods(
        modsDir: String,
        modId: String,
        textureName: String
    ): ByteArray? {
        return withFileIO {
            val dir = File(modsDir)
            if (!dir.exists()) return@withFileIO null

            val jarFiles = dir.listFiles { f -> f.extension == "jar" } ?: return@withFileIO null

            for (jarFile in jarFiles) {
                try {
                    val zip = ZipFile(jarFile)
                    try {
                        val entryPath = "assets/$modId/textures/block/$textureName.png"
                        val entry = zip.getEntry(entryPath)
                        if (entry != null) {
                            return@withFileIO zip.getInputStream(entry).readBytes()
                        }
                    } finally {
                        zip.close()
                    }
                } catch (_: Exception) { }
            }

            null
        }
    }

    companion object {
        private val BLOCK_TEXTURE_REGEX = Regex("assets/([^/]+)/textures/block/(.+\\.png)")
    }
}