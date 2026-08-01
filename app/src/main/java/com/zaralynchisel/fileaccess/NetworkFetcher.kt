package com.zaralynchisel.fileaccess

import com.zaralynchisel.utils.Logger
import com.zaralynchisel.utils.withFileIO
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads Minecraft textures from the network as a fallback.
 * Uses configurable mirror URLs and caches results locally.
 */
class NetworkFetcher(private val cacheDir: File) {

    companion object {
        private const val DEFAULT_MIRROR = "https://resources.download.minecraft.net"
        private const val CACHE_PREFIX = "tex_"
        private const val TIMEOUT_MS = 10_000
    }

    /**
     * Download a texture by its hash.
     * @param hash SHA-1 hash of the texture file
     * @param mirrorUrl Base URL of the texture mirror
     * @return The texture bytes, or null if download failed
     */
    suspend fun downloadTexture(hash: String, mirrorUrl: String = DEFAULT_MIRROR): ByteArray? {
        // Check cache first
        val cached = getFromCache(hash)
        if (cached != null) {
            Logger.d("Texture $hash loaded from cache")
            return cached
        }

        return withFileIO {
            try {
                // Mojang's resource CDN stores files as <hash[0..2]>/<hash>.
                val url = URL("$mirrorUrl/${hash.take(2)}/$hash")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Logger.w("Download failed for $hash: HTTP $responseCode")
                    return@withFileIO null
                }

                val data = connection.inputStream.readBytes()
                connection.disconnect()

                // Save to cache
                saveToCache(hash, data)

                Logger.d("Downloaded texture $hash (${data.size} bytes)")
                data
            } catch (e: Exception) {
                Logger.e("Network download failed for $hash", e)
                null
            }
        }
    }

    /**
     * Download multiple textures in sequence.
     */
    suspend fun downloadTextures(
        hashes: List<String>,
        mirrorUrl: String = DEFAULT_MIRROR,
        onProgress: (downloaded: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, ByteArray> {
        val results = mutableMapOf<String, ByteArray>()
        for ((index, hash) in hashes.withIndex()) {
            val data = downloadTexture(hash, mirrorUrl)
            if (data != null) {
                results[hash] = data
            }
            onProgress(index + 1, hashes.size)
        }
        return results
    }

    /**
     * Get a cached texture by hash.
     */
    private fun getFromCache(hash: String): ByteArray? {
        return try {
            val cacheFile = File(cacheDir, "$CACHE_PREFIX$hash")
            if (cacheFile.exists()) {
                cacheFile.readBytes()
            } else null
        } catch (e: Exception) {
            Logger.e("Failed to read cache for $hash", e)
            null
        }
    }

    /**
     * Save a texture to the local cache.
     */
    private fun saveToCache(hash: String, data: ByteArray) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val cacheFile = File(cacheDir, "$CACHE_PREFIX$hash")
            cacheFile.writeBytes(data)
        } catch (e: Exception) {
            Logger.e("Failed to cache texture $hash", e)
        }
    }

    /**
     * Clear the entire texture cache.
     */
    fun clearCache(): Boolean {
        return try {
            val files = cacheDir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.name.startsWith(CACHE_PREFIX)) {
                        file.delete()
                    }
                }
            }
            Logger.d("Texture cache cleared")
            true
        } catch (e: Exception) {
            Logger.e("Failed to clear texture cache", e)
            false
        }
    }

    /**
     * Get the current cache size in bytes.
     */
    fun getCacheSize(): Long {
        return try {
            val files = cacheDir.listFiles() ?: return 0L
            files.filter { it.name.startsWith(CACHE_PREFIX) }.sumOf { it.length() }
        } catch (_: Exception) { 0L }
    }
}