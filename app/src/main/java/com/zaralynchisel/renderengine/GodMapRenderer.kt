package com.zaralynchisel.renderengine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.zaralynchisel.editioncore.ChunkInfo
import com.zaralynchisel.editioncore.SelectionArea
import kotlin.math.floor

/**
 * 2D map renderer for God Mode.
 * Renders a top-down view of the world with chunk grid, selection overlays,
 * and biome/height coloring.
 */
class GodMapRenderer {

    data class RenderConfig(
        val viewX: Float = 0f,
        val viewZ: Float = 0f,
        val zoom: Float = 1f,
        val showGrid: Boolean = true,
        val showBiomeColors: Boolean = true,
        val selectionArea: SelectionArea? = null,
        /** Clipboard footprint preview, centred on the view so the user sees where a
         *  paste will land. null when nothing is copied. */
        val clipboardFootprint: ClipboardFootprint? = null,
        val chunkSize: Float = 16f  // Pixels per chunk at zoom=1
    )

    /** Where the clipboard will land if pasted now: the offset from the view-centre
     *  chunk to the clipboard's top-left chunk, plus the clipboard's chunk span. */
    data class ClipboardFootprint(
        val centerOffsetX: Int,  // clipOriginChunkX - clipCenterX
        val centerOffsetZ: Int,  // clipOriginChunkZ - clipCenterZ
        val widthChunks: Int,    // clipMaxX - clipOriginChunkX (inclusive span; 0 = 1 chunk)
        val heightChunks: Int    // clipMaxZ - clipOriginChunkZ
    )

    data class RenderResult(
        val bitmap: Bitmap,
        val visibleChunks: Int
    )

    private val gridPaint = Paint().apply {
        color = 0x33FFFFFF.toInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val selectionPaint = Paint().apply {
        color = 0x664CAF50.toInt()
        style = Paint.Style.FILL
    }

    private val selectionBorderPaint = Paint().apply {
        color = 0xFF4CAF50.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val clipboardPreviewPaint = Paint().apply {
        // Translucent blue fill so it reads as "paste target" at a glance.
        color = 0x552196F3.toInt()
        style = Paint.Style.FILL
    }

    private val clipboardBorderPaint = Paint().apply {
        color = 0xFF2196F3.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    /**
     * Render the god map view.
     * @param width Viewport width in pixels
     * @param height Viewport height in pixels
     * @param chunks List of chunks to render (with their metadata)
     * @param config Render configuration
     */
    fun render(
        width: Int,
        height: Int,
        chunks: List<ChunkInfo>,
        config: RenderConfig
    ): RenderResult {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background (void)
        canvas.drawColor(0xFF1A1A2E.toInt())

        val scaledChunkSize = config.chunkSize * config.zoom
        val centerX = width / 2f
        val centerZ = height / 2f

        // Offset to center the view on (viewX, viewZ)
        val offsetX = centerX - config.viewX * scaledChunkSize
        val offsetZ = centerZ - config.viewZ * scaledChunkSize

        // Draw chunks. Following mcasaenk's ChunkRenderer: only real surface block
        // data is drawn; empty / not-yet-loaded / all-air chunks are left transparent
        // (they show the void background) instead of being filled with a fake solid
        // color — that fake fill was why every chunk appeared as a single uniform color.
        var visibleCount = 0
        for (chunk in chunks) {
            // Skip ungenerated / all-air chunks entirely (transparent, like mcasaenk).
            if (chunk.isEmpty) continue
            // No surface data loaded yet → don't fabricate a color; wait for the async loader.
            if (!chunk.hasSurfaceData) continue
            // Surface detail is only visible when the chunk is large enough on screen.
            if (scaledChunkSize < 0.5f) continue

            val screenX = chunk.x * scaledChunkSize + offsetX
            val screenZ = chunk.z * scaledChunkSize + offsetZ

            // Frustum culling
            if (screenX + scaledChunkSize < 0 || screenX > width ||
                screenZ + scaledChunkSize < 0 || screenZ > height) {
                continue
            }

            visibleCount++

            val rect = RectF(screenX, screenZ, screenX + scaledChunkSize, screenZ + scaledChunkSize)
            drawChunkSurface(canvas, chunk, rect)
        }

        // Draw grid lines
        if (config.showGrid) {
            val gridStartX = offsetX % scaledChunkSize
            val gridStartZ = offsetZ % scaledChunkSize

            var x = gridStartX
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                x += scaledChunkSize
            }

            var z = gridStartZ
            while (z < height) {
                canvas.drawLine(0f, z, width.toFloat(), z, gridPaint)
                z += scaledChunkSize
            }
        }

        // Draw selection overlay
        config.selectionArea?.let { drawSelection(canvas, it, config, offsetX, offsetZ, scaledChunkSize) }
        config.clipboardFootprint?.let {
            drawClipboardPreview(canvas, it, config, offsetX, offsetZ, scaledChunkSize)
        }

        return RenderResult(bitmap, visibleCount)
    }

    private fun drawSelection(
        canvas: Canvas,
        selection: SelectionArea,
        config: RenderConfig,
        offsetX: Float,
        offsetZ: Float,
        scaledChunkSize: Float
    ) {
        when (selection) {
            is SelectionArea.Rectangle -> {
                val left = selection.minChunkX * scaledChunkSize + offsetX
                val top = selection.minChunkZ * scaledChunkSize + offsetZ
                val right = (selection.maxChunkX + 1) * scaledChunkSize + offsetX
                val bottom = (selection.maxChunkZ + 1) * scaledChunkSize + offsetZ
                val rect = RectF(left, top, right, bottom)
                canvas.drawRect(rect, selectionPaint)
                canvas.drawRect(rect, selectionBorderPaint)
            }
            is SelectionArea.Circle -> {
                val cx = selection.centerChunkX * scaledChunkSize + offsetX + scaledChunkSize / 2
                val cz = selection.centerChunkZ * scaledChunkSize + offsetZ + scaledChunkSize / 2
                val radius = selection.radiusChunks * scaledChunkSize
                canvas.drawCircle(cx, cz, radius, selectionPaint)
                canvas.drawCircle(cx, cz, radius, selectionBorderPaint)
            }
        }
    }

    /** Draw the semi-transparent blue rectangle showing where the clipboard will
     *  land if pasted. The footprint is centred on the view centre (floor(view))
     *  plus the clipboard's origin-to-centre offset, so it tracks panning live. */
    private fun drawClipboardPreview(
        canvas: Canvas,
        footprint: ClipboardFootprint,
        config: RenderConfig,
        offsetX: Float,
        offsetZ: Float,
        scaledChunkSize: Float
    ) {
        val originX = floor(config.viewX).toInt() + footprint.centerOffsetX
        val originZ = floor(config.viewZ).toInt() + footprint.centerOffsetZ
        val left = originX * scaledChunkSize + offsetX
        val top = originZ * scaledChunkSize + offsetZ
        // +1 because widthChunks/heightChunks are inclusive spans (0 = one chunk).
        val right = (originX + footprint.widthChunks + 1) * scaledChunkSize + offsetX
        val bottom = (originZ + footprint.heightChunks + 1) * scaledChunkSize + offsetZ
        val rect = RectF(left, top, right, bottom)
        canvas.drawRect(rect, clipboardPreviewPaint)
        canvas.drawRect(rect, clipboardBorderPaint)
    }

    /**
     * Convert screen coordinates to chunk coordinates.
     */
    fun screenToChunk(
        screenX: Float,
        screenZ: Float,
        width: Int,
        height: Int,
        config: RenderConfig
    ): Pair<Int, Int> {
        val scaledChunkSize = config.chunkSize * config.zoom
        val centerX = width / 2f
        val centerZ = height / 2f
        val offsetX = centerX - config.viewX * scaledChunkSize
        val offsetZ = centerZ - config.viewZ * scaledChunkSize

        val chunkX = ((screenX - offsetX) / scaledChunkSize).toInt()
        val chunkZ = ((screenZ - offsetZ) / scaledChunkSize).toInt()
        return Pair(chunkX, chunkZ)
    }

    /**
     * Draw a 16×16 pixel surface bitmap for a chunk.
     */
    private fun drawChunkSurface(
        canvas: android.graphics.Canvas,
        chunk: ChunkInfo,
        rect: RectF
    ) {
        val colors = chunk.surfaceColors ?: return
        // Build or retrieve cached bitmap (keyed by dimension + chunk coords so chunks
        // from different dimensions never share a surface bitmap).
        val cacheKey = Triple(chunk.dimension.ordinal, chunk.x, chunk.z)
        val bitmap = chunkSurfaceCache.getOrPut(cacheKey) {
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        }
        val pixels = IntArray(256)
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val colorId = colors[z * 16 + x]
                pixels[z * 16 + x] = if (colorId > 0) MapColorPalette.getColor(colorId) else 0x00000000.toInt()
            }
        }
        bitmap.setPixels(pixels, 0, 16, 0, 0, 16, 16)
        val src = Rect(0, 0, 16, 16)
        val dst = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        canvas.drawBitmap(bitmap, src, dst, null)
    }

    private val chunkSurfaceCache = mutableMapOf<Triple<Int, Int, Int>, Bitmap>()
}