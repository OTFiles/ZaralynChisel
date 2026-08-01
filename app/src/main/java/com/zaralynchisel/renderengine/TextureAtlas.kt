package com.zaralynchisel.renderengine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import com.zaralynchisel.utils.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Texture atlas backed by a GL_TEXTURE_2D_ARRAY (one layer per texture).
 *
 * Layers are assigned sequentially and NEVER move, so a mesh built against a
 * layer id stays valid when new textures arrive — new layers are only appended
 * and uploaded with glTexSubImage3D on the GL thread. All tiles are resized to
 * 16×16 (matching the vanilla resolution; higher-res packs are downscaled).
 *
 * Layer 0 is a magenta/black checker placeholder ("missing texture") used for
 * any texture that hasn't resolved yet or failed to load.
 */
class TextureAtlas {

    companion object {
        const val TILE_SIZE = 16
        const val LAYER_COUNT = 512
        const val MISSING_LAYER = 0
        const val MISSING_PATH = "builtin:missing"
    }

    /** texture path ("minecraft:block/grass_block_top") → layer id. */
    private val pathToLayer = HashMap<String, Int>()
    private val layerBitmaps = arrayOfNulls<Bitmap>(LAYER_COUNT)
    private val pendingUploads = ConcurrentLinkedQueue<Pair<Int, Bitmap>>()
    private var nextLayer = 1 // layer 0 = built-in missing texture

    /** GL texture handle, valid only on the GL thread after [createOnGl]. */
    private var glTexture = 0

    init {
        // Built-in checker placeholder.
        val missing = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val px = IntArray(TILE_SIZE * TILE_SIZE)
        for (i in px.indices) {
            val row = i / TILE_SIZE
            val col = i % TILE_SIZE
            px[i] = if (((row / 4 + col / 4) and 1) == 0) 0xFFFF00FF.toInt() else 0xFF000000.toInt()
        }
        missing.setPixels(px, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        layerBitmaps[MISSING_LAYER] = missing
        pathToLayer[MISSING_PATH] = MISSING_LAYER
    }

    /** Layer for a path; [MISSING_LAYER] while the texture is unknown. Thread-safe. */
    @Synchronized
    fun layerFor(path: String): Int = pathToLayer[path] ?: MISSING_LAYER

    /** True if a real (non-placeholder) texture exists for this path. */
    @Synchronized
    fun has(path: String): Boolean = pathToLayer[path] != null && pathToLayer[path] != MISSING_LAYER

    /**
     * Register a decoded texture (PNG bytes) under [path]. Returns true when a NEW
     * layer was created (callers should rebuild meshes that referenced the missing
     * layer for this path). Thread-safe; upload happens later on the GL thread.
     */
    @Synchronized
    fun register(path: String, pngBytes: ByteArray): Boolean {
        if (pathToLayer.containsKey(path)) return false
        if (nextLayer >= LAYER_COUNT) {
            Logger.e("TextureAtlas full ($LAYER_COUNT layers), texture dropped: $path")
            return false
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        } catch (e: Exception) {
            Logger.e("Failed to decode texture $path", e)
            null
        } ?: return false

        val layer = nextLayer++
        pathToLayer[path] = layer
        val scaled = Bitmap.createScaledBitmap(bmp, TILE_SIZE, TILE_SIZE, true)
        layerBitmaps[layer] = scaled
        pendingUploads.add(layer to scaled)
        return true
    }

    /** GL thread: (re)create the array texture and upload any pending layers. */
    fun uploadPending() {
        if (glTexture == 0) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            glTexture = tex[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, glTexture)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_2D_ARRAY, 0, GLES30.GL_RGBA8,
                TILE_SIZE, TILE_SIZE, LAYER_COUNT, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            // Re-upload everything already registered (e.g. the missing layer).
            for (i in 0 until nextLayer) {
                layerBitmaps[i]?.let { uploadLayer(i, it) }
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)
            return
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, glTexture)
        while (true) {
            val entry = pendingUploads.poll() ?: break
            uploadLayer(entry.first, entry.second)
        }
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)
    }

    /** Bind the array texture (GL thread). */
    fun bind() {
        if (glTexture != 0) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, glTexture)
    }

    /** Delete the GL texture (GL thread). */
    fun deleteOnGl() {
        if (glTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(glTexture), 0)
            glTexture = 0
        }
    }

    private fun uploadLayer(layer: Int, bmp: Bitmap) {
        val buf = ByteBuffer.allocateDirect(TILE_SIZE * TILE_SIZE * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.position(0)
        GLES30.glTexSubImage3D(
            GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer,
            TILE_SIZE, TILE_SIZE, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf
        )
    }
}
