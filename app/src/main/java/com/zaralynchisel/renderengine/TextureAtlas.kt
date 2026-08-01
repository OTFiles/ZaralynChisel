package com.zaralynchisel.renderengine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.opengl.GLES30
import com.zaralynchisel.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Texture atlas: all tiles are packed into a single 2D texture and sampled with
 * GL_NEAREST (pixelated, like vanilla Minecraft) + mipmaps so distant terrain
 * stays crisp instead of aliasing into moiré patterns.
 *
 * Tiles are 16×16 and assigned sequentially — never moved — so meshes built
 * against a tile id stay valid when new textures arrive. New tiles are drawn
 * into a CPU-side atlas bitmap and uploaded with glTexSubImage2D on the GL
 * thread. Tile 0 is the magenta/black checker placeholder.
 *
 * (A GL_TEXTURE_2D_ARRAY was used before, but array-texture mipmap generation
 * is unreliable on some drivers, and LINEAR filtering blurred the pixels.)
 */
class TextureAtlas {

    companion object {
        const val TILE_SIZE = 16
        const val GRID = 32                // 32×32 = 1024 tiles
        const val ATLAS_SIZE = TILE_SIZE * GRID // 512×512
        const val MISSING_TILE = 0
        const val MISSING_PATH = "builtin:missing"

        /** Tile-local UV fraction (0..1 across one tile). */
        const val TILE_UV = 1f / GRID
    }

    private val pathToTile = HashMap<String, Int>()
    private val pendingTiles = ConcurrentLinkedQueue<Pair<Int, Bitmap>>()
    private var nextTile = 1

    /** CPU-side accumulated atlas; the GL texture mirrors it. */
    private val atlasBitmap = Bitmap.createBitmap(ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888)

    /** GL texture handle, valid only on the GL thread after [uploadPending]. */
    private var glTexture = 0

    init {
        // Built-in checker placeholder in tile 0.
        val missing = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val px = IntArray(TILE_SIZE * TILE_SIZE)
        for (i in px.indices) {
            val row = i / TILE_SIZE
            val col = i % TILE_SIZE
            px[i] = if (((row / 4 + col / 4) and 1) == 0) 0xFFFF00FF.toInt() else 0xFF000000.toInt()
        }
        missing.setPixels(px, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        drawTile(0, missing)
        pathToTile[MISSING_PATH] = MISSING_TILE
    }

    /** Tile id for a path; [MISSING_TILE] while the texture is unknown. Thread-safe. */
    @Synchronized
    fun tileFor(path: String): Int = pathToTile[path] ?: MISSING_TILE

    /** True if a real (non-placeholder) texture exists for this path. */
    @Synchronized
    fun has(path: String): Boolean = pathToTile[path] != null && pathToTile[path] != MISSING_TILE

    /** Atlas UV origin (u0, v0) for a tile. */
    @Synchronized
    fun uvOrigin(tile: Int): FloatArray {
        val t = tile.coerceAtLeast(0)
        return floatArrayOf((t % GRID) * TILE_UV, (t / GRID) * TILE_UV)
    }

    /**
     * Register a decoded texture (PNG bytes) under [path]. Returns true when a NEW
     * tile was created (callers should rebuild meshes that referenced the missing
     * tile for this path). Thread-safe; upload happens later on the GL thread.
     */
    @Synchronized
    fun register(path: String, pngBytes: ByteArray): Boolean {
        if (pathToTile.containsKey(path)) return false
        if (nextTile >= GRID * GRID) {
            Logger.e("TextureAtlas full ($GRID×$GRID tiles), texture dropped: $path")
            return false
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        } catch (e: Exception) {
            Logger.e("Failed to decode texture $path", e)
            null
        } ?: return false

        val tile = nextTile++
        pathToTile[path] = tile
        val scaled = Bitmap.createScaledBitmap(bmp, TILE_SIZE, TILE_SIZE, true)
        // GL texture row 0 (v=0) is the image's LAST row in memory, so flip the
        // tile vertically to keep the texture upright when sampled.
        val flipped = Bitmap.createBitmap(scaled, 0, 0, TILE_SIZE, TILE_SIZE,
            android.graphics.Matrix().apply { setScale(1f, -1f) }, true)
        drawTile(tile, flipped)
        pendingTiles.add(tile to flipped)
        return true
    }

    /** GL thread: (re)create the atlas texture and upload any pending tiles. */
    fun uploadPending() {
        if (glTexture == 0) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            glTexture = tex[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTexture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST_MIPMAP_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                ATLAS_SIZE, ATLAS_SIZE, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            uploadAtlasBitmap()
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            return
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTexture)
        while (true) {
            val entry = pendingTiles.poll() ?: break
            val t = entry.first
            val x = (t % GRID) * TILE_SIZE
            val y = (t / GRID) * TILE_SIZE
            val buf = tileBuffer(entry.second)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, x, y, TILE_SIZE, TILE_SIZE,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
            )
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    /** Bind the atlas texture (GL thread). */
    fun bind() {
        if (glTexture != 0) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glTexture)
    }

    /** Delete the GL texture (GL thread). */
    fun deleteOnGl() {
        if (glTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(glTexture), 0)
            glTexture = 0
        }
    }

    private fun drawTile(tile: Int, bmp: Bitmap) {
        val x = (tile % GRID) * TILE_SIZE
        val y = (tile / GRID) * TILE_SIZE
        Canvas(atlasBitmap).drawBitmap(bmp, x.toFloat(), y.toFloat(), null)
    }

    private fun uploadAtlasBitmap() {
        val buf = ByteBuffer.allocateDirect(ATLAS_SIZE * ATLAS_SIZE * 4).order(ByteOrder.nativeOrder())
        atlasBitmap.copyPixelsToBuffer(buf)
        buf.position(0)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, ATLAS_SIZE, ATLAS_SIZE,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
        )
    }

    private fun tileBuffer(bmp: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(TILE_SIZE * TILE_SIZE * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.position(0)
        return buf
    }
}
