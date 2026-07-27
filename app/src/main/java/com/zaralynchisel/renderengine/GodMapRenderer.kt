package com.zaralynchisel.renderengine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.zaralynchisel.editioncore.ChunkInfo
import com.zaralynchisel.editioncore.SelectionArea

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
        val chunkSize: Float = 16f  // Pixels per chunk at zoom=1
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

    private val chunkPresentPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val chunkEmptyPaint = Paint().apply {
        color = 0x33444444.toInt()
        style = Paint.Style.FILL
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

        // Draw chunks
        var visibleCount = 0
        for (chunk in chunks) {
            val screenX = chunk.x * scaledChunkSize + offsetX
            val screenZ = chunk.z * scaledChunkSize + offsetZ

            // Frustum culling
            if (screenX + scaledChunkSize < 0 || screenX > width ||
                screenZ + scaledChunkSize < 0 || screenZ > height) {
                continue
            }

            visibleCount++

            val rect = RectF(screenX, screenZ, screenX + scaledChunkSize, screenZ + scaledChunkSize)

            if (chunk.isEmpty) {
                canvas.drawRect(rect, chunkEmptyPaint)
            } else if (scaledChunkSize >= 0.5f && chunk.hasSurfaceData) {
                // Render 16×16 pixel surface data
                drawChunkSurface(canvas, chunk, rect)
            } else {
                // Fallback: terrain-based solid color
                chunkPresentPaint.color = terrainColor(chunk.x, chunk.z, chunk.averageHeight, chunk.dimension)
                canvas.drawRect(rect, chunkPresentPaint)
            }
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

    /**
     * Terrain-simulated color based on chunk coordinates and dimension.
     * Uses value noise to produce terrain-like coloring:
     * - Low areas: green (grass)
     * - Mid elevations: brown (dirt/hills)
     * - High elevations: gray (stone/mountains)
     * - Very high: white (snow)
     */
    private fun terrainColor(x: Int, z: Int, height: Int, dimension: com.zaralynchisel.editioncore.DimensionType): Int {
        // Use actual height if available
        val h = if (height > 0) {
            height.toFloat()
        } else {
            // Simulated height from value noise
            simulatedHeight(x, z, dimension)
        }

        return when {
            dimension == com.zaralynchisel.editioncore.DimensionType.NETHER -> {
                // Nether: dark red tones
                val v = ((simulatedHeight(x, z, dimension) % 40 + 40) / 40f).coerceIn(0f, 1f)
                val r = (140 + v * 60).toInt()
                val g = (30 + v * 20).toInt()
                val b = (30 + v * 10).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            dimension == com.zaralynchisel.editioncore.DimensionType.END -> {
                0xFFD4C898.toInt()  // End: pale yellow
            }
            // Overworld height-based coloring
            h < 50 -> 0xFF4040FF.toInt()  // ocean blue (water)
            h < 55 -> 0xFF3E7A28.toInt()  // deep green / river
            h < 63 -> 0xFF7FB238.toInt()  // grass green (MapColor 1)
            h < 68 -> 0xFF8F9C32.toInt()  // light grass
            h < 75 -> 0xFF976D4D.toInt()  // brown / dirt (MapColor 10)
            h < 90 -> 0xFF9D9D9D.toInt()  // stone gray
            h < 110 -> 0xFFB0B0B0.toInt() // light stone
            h < 130 -> 0xFFC8C8C8.toInt() // high mountain
            else -> 0xFFE8E8E8.toInt()    // snow
        }
    }

    /**
     * Simple value noise for terrain simulation.
     * Uses integer hash (Wang mix) to avoid repeating patterns.
     */
    private fun simulatedHeight(x: Int, z: Int, dimension: com.zaralynchisel.editioncore.DimensionType): Float {
        val dimMix = when (dimension) {
            com.zaralynchisel.editioncore.DimensionType.OVERWORLD -> 0
            com.zaralynchisel.editioncore.DimensionType.NETHER -> 0x55555555
            com.zaralynchisel.editioncore.DimensionType.END -> 0x33333333
        }
        // Integer hash function (Wang hash / xxHash-style mix)
        var h = x * 374761393 + z * 668265263 + dimMix
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        // Map to 0..1 range
        val v = ((h.toLong() and 0xFFFFFFFFL).toFloat()) / 4294967296f
        val curved = (v - 0.5f) * 1.8f + 0.5f  // expand range slightly
        return 40f + curved.coerceIn(0f, 1f) * 100f
    }
}