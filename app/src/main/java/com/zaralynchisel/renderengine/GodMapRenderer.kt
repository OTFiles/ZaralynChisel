package com.zaralynchisel.renderengine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
            } else {
                chunkPresentPaint.color = chunkColor(chunk.x, chunk.z, chunk.dimension)
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
     * Deterministic pseudo-random color based on chunk position and dimension.
     */
    private fun chunkColor(x: Int, z: Int, dimension: com.zaralynchisel.editioncore.DimensionType): Int {
        val dimOffset = when (dimension) {
            com.zaralynchisel.editioncore.DimensionType.OVERWORLD -> 0
            com.zaralynchisel.editioncore.DimensionType.NETHER -> 0x55555555
            com.zaralynchisel.editioncore.DimensionType.END -> 0x33333333
        }
        val h = ((x.toLong() * 0x9E3779B9L) xor (z.toLong() * 0x517CC1B7L) + dimOffset).toInt()
        val r = ((h shr 16) and 0xFF) % 96 + 80
        val g = ((h shr 8) and 0xFF) % 96 + 64
        val b = (h and 0xFF) % 80 + 48
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}