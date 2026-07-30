package com.zaralynchisel.ui.godmode
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zaralynchisel.editioncore.*
import com.zaralynchisel.fileaccess.WorldSelector
import com.zaralynchisel.fileaccess.WorldCache
import com.zaralynchisel.renderengine.GodMapRenderer
import com.zaralynchisel.ZaralynChiselApp
import com.zaralynchisel.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GodModeScreen(
    worldPath: String,
    onSwitchToPlayer: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as ZaralynChiselApp
    val worldSelector = remember { WorldSelector(context).also { it.safAccess = app.safAccess } }
    val renderer = remember { GodMapRenderer() }

    var worldData by remember { mutableStateOf<WorldData?>(null) }
    var chunks by remember { mutableStateOf<List<ChunkInfo>>(emptyList()) }
    /** Surface (color) data for loaded chunks, keyed by (dim,x,z). Decoupled from the
     *  big `chunks` list so incremental loads from the background coroutine reliably
     *  propagate to the renderer (the list-copy + reassign pattern was lossy). */
    val surfaceCache = remember { androidx.compose.runtime.mutableStateMapOf<Long, ChunkInfo>() }
    /** Chunk keys confirmed to be all-air stubs, so the loader skips re-reading them. */
    val emptyCache = remember { androidx.compose.runtime.mutableStateMapOf<Long, Unit>() }

    /** Stable key for a chunk position across dimensions. */
    fun chunkKey(dim: DimensionType, x: Int, z: Int): Long =
        (dim.ordinal.toLong() shl 48) or ((x.toLong() and 0xFFFFFFL) shl 24) or (z.toLong() and 0xFFFFFFL)
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // View state
    var viewX by remember { mutableFloatStateOf(0f) }
    var viewZ by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var showGrid by remember { mutableStateOf(true) }
    var isSelectMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<SelectionArea?>(null) }
    // Drag selection state (chunk coords) + canvas size for screen↔chunk conversion
    var selStartCx by remember { mutableIntStateOf(0) }
    var selStartCz by remember { mutableIntStateOf(0) }
    var canvasW by remember { mutableIntStateOf(1) }
    var canvasH by remember { mutableIntStateOf(1) }

    // Batch operation state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isBatchRunning by remember { mutableStateOf(false) }
    var clipboard by remember { mutableStateOf<ChunkClipboard?>(null) }
    var showPermDialog by remember { mutableStateOf(false) }
    val batchProcessor = remember(worldPath) { BlockBatchProcessor(worldPath) }

    /** Convert a screen point (px, relative to canvas) to chunk coords, using the
     *  same transform as the renderer. */
    fun screenToChunk(sx: Float, sz: Float): Pair<Int, Int> {
        val scaled = 16f * zoom
        val offsetX = canvasW / 2f - viewX * scaled
        val offsetZ = canvasH / 2f - viewZ * scaled
        val cx = ((sx - offsetX) / scaled).toInt()
        val cz = ((sz - offsetZ) / scaled).toInt()
        return cx to cz
    }

    /** Drop cached surface/empty data for a chunk rectangle so the loader re-reads
     *  it (and the map re-renders) after a batch operation changes the region file. */
    fun invalidateRange(dim: DimensionType, minX0: Int, minZ0: Int, maxX0: Int, maxZ0: Int) {
        var minX = minX0; var maxX = maxX0
        var minZ = minZ0; var maxZ = maxZ0
        if (minX > maxX) { val t = minX; minX = maxX; maxX = t }
        if (minZ > maxZ) { val t = minZ; minZ = maxZ; maxZ = t }
        for (x in minX..maxX) for (z in minZ..maxZ) {
            val k = chunkKey(dim, x, z)
            surfaceCache.remove(k)
            emptyCache.remove(k)
        }
    }

    /** True if the app may write to world files via the File API. */
    fun hasWritePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** Open the system "All files access" settings so the user can grant write
     *  permission (the normal RequestPermission contract cannot grant
     *  MANAGE_EXTERNAL_STORAGE on Android 11+). */
    fun requestAllFilesAccess() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
            )
            context.startActivity(intent)
        }
    }

    // Current dimension info
    val currentDim by remember(worldData) {
        derivedStateOf {
            worldData?.dimensionPaths?.keys?.firstOrNull() ?: DimensionType.OVERWORLD
        }
    }

    /**
     * Center the view on actual generated/played terrain instead of spawn.
     * Server/NeoForge saves commonly have "structure_starts" stubs (no terrain)
     * around spawn; the most recently modified overworld chunk reliably lies
     * inside the generated area. Falls back to spawn when no chunks/timestamps.
     */
    fun centerOnGeneratedTerrain(allChunks: List<ChunkInfo>, info: WorldData) {
        // Center on the chunk the player is actually standing in. The player stands in
        // played (generated) terrain by definition, so this always opens the map on real
        // terrain without any chunk-parsing heuristics. Fall back to spawn, then to the
        // most-recently-modified overworld chunk if there's no Player tag (server saves).
        when {
            info.playerX != null && info.playerZ != null -> {
                viewX = (info.playerX / 16.0).toFloat()
                viewZ = (info.playerZ / 16.0).toFloat()
                Logger.i("Centered on player chunk (${viewX.toInt()},${viewZ.toInt()}) pos=(${info.playerX},${info.playerZ})")
            }
            info.spawnX != 0 || info.spawnZ != 0 -> {
                viewX = info.spawnX / 16f
                viewZ = info.spawnZ / 16f
                Logger.i("Centered on spawn (${info.spawnX},${info.spawnZ})")
            }
            else -> {
                val overworld = allChunks.filter { it.dimension == DimensionType.OVERWORLD }
                val recent = overworld.sortedByDescending { it.timestamp }.take(64)
                if (recent.isNotEmpty() && recent.first().timestamp > 0) {
                    viewX = recent.map { it.x }.average().toFloat()
                    viewZ = recent.map { it.z }.average().toFloat()
                    Logger.i("Centered on newest-terrain centroid (${viewX.toInt()},${viewZ.toInt()})")
                } else {
                    viewX = 0f; viewZ = 0f
                    Logger.i("Centered on origin (0,0)")
                }
            }
        }
    }

    // Load surface data for VISIBLE chunks only (viewport-based)
    // Surface loader: a persistent loop keyed only on the world, NOT on view/zoom.
    // Keying on viewX/viewZ/zoom cancelled the coroutine mid-batch on every pan
    // gesture, so `chunks = updatedChunks` was never reached and the renderer always
    // saw withSurface=0. Instead we poll the current view each iteration and load a
    // batch of the nearest not-yet-loaded chunks; panning just changes which chunks
    // the next iteration picks up, without cancelling the load in flight.
    LaunchedEffect(worldPath, worldData) {
        if (worldData == null) return@LaunchedEffect
        val worldPathLocal = worldPath
        while (isActive) {
            if (chunks.isEmpty()) { delay(100); continue }
            val curDim = currentDim
            val cx = viewX
            val cz = viewZ
            val viewRadius = (200f / zoom).toInt().coerceIn(10, 500)
            val minX = viewX.toInt() - viewRadius
            val maxX = viewX.toInt() + viewRadius
            val minZ = viewZ.toInt() - viewRadius
            val maxZ = viewZ.toInt() + viewRadius

            val visible = chunks.filter { chunk ->
                chunk.dimension == curDim &&
                chunk.x in minX..maxX && chunk.z in minZ..maxZ &&
                surfaceCache[chunkKey(curDim, chunk.x, chunk.z)] == null &&
                emptyCache[chunkKey(curDim, chunk.x, chunk.z)] == null
            }.sortedBy {
                val dx = it.x - cx; val dz = it.z - cz
                dx * dx + dz * dz
            }.take(30)

            if (visible.isEmpty()) { delay(150); continue }

            var loaded = 0; var failed = 0
            for (chunk in visible) {
                val surface = worldSelector.loadChunkSurface(
                    worldPathLocal, chunk.x, chunk.z, chunk.dimension
                )
                if (surface != null) {
                    loaded++
                    val allZero = !surface.any { it != 0 }
                    if (allZero) {
                        emptyCache[chunkKey(curDim, chunk.x, chunk.z)] = Unit
                    } else {
                        surfaceCache[chunkKey(curDim, chunk.x, chunk.z)] = chunk.copy(
                            surfaceColors = surface,
                            averageHeight = surface.average().toInt()
                        )
                    }
                } else { failed++ }
            }
            Logger.i("Surface batch: loaded=$loaded failed=$failed totalVisible=${visible.size} totalWithSurface=${surfaceCache.size} view=(${cx.toInt()},${cz.toInt()})")
            // Yield so recomposition (incl. surface data propagation to the Canvas)
            // can happen between batches, and so we don't starve the main thread.
            delay(50)
        }
    }

    // Load world data — use cache to avoid re-scanning
    LaunchedEffect(worldPath) {
        isLoading = true
        try {
            // Try cache first
            val cached = WorldCache.get(worldPath)
            if (cached != null) {
                worldData = cached.first
                chunks = cached.second
                centerOnGeneratedTerrain(cached.second, cached.first)
                Logger.i("Loaded ${chunks.size} chunks from cache")
                isLoading = false
                return@LaunchedEffect
            }

            val info = worldSelector.loadWorldInfo(worldPath)
            if (info != null) {
                worldData = info
                val allChunks = mutableListOf<ChunkInfo>()
                for (dim in info.dimensionPaths.keys) {
                    val dimPath = info.dimensionPaths[dim] ?: continue
                    Logger.i("Scanning chunks in $dim at $dimPath")
                    val scanned = worldSelector.scanChunks(dimPath)
                    allChunks.addAll(scanned)
                }
                chunks = allChunks.ifEmpty {
                    listOf(
                        ChunkInfo(0, 0, DimensionType.OVERWORLD),
                        ChunkInfo(1, 0, DimensionType.OVERWORLD),
                        ChunkInfo(0, 1, DimensionType.OVERWORLD),
                        ChunkInfo(1, 1, DimensionType.OVERWORLD)
                    )
                }
                // Center on generated/played terrain rather than spawn: many worlds
                // (server/NeoForge saves) have "structure_starts" stubs around spawn
                // with no terrain, so spawn would render as a blank screen.
                centerOnGeneratedTerrain(chunks, info)
                // Cache the result
                WorldCache.put(worldPath, info, chunks)
                Logger.i("Loaded ${chunks.size} chunks total")
            } else {
                errorMessage = "Failed to load world data"
            }
        } catch (e: Exception) {
            Logger.e("Error loading world", e)
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = worldData?.worldName ?: "God Mode",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (worldData != null) {
                            Text(
                                text = "v${worldData!!.dataVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSwitchToPlayer) {
                        Icon(Icons.Default.VideogameAsset, contentDescription = "切换至游戏模式")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ToolbarButton(
                        icon = Icons.Default.Delete,
                        label = "删除",
                        enabled = selection != null && !isBatchRunning,
                        onClick = { showDeleteDialog = true }
                    )
                    ToolbarButton(
                        icon = Icons.Default.ContentCopy,
                        label = "复制",
                        enabled = selection != null && !isBatchRunning,
                        onClick = {
                            if (selection != null) {
                                scope.launch {
                                    isBatchRunning = true
                                    try {
                                        clipboard = batchProcessor.copyChunks(currentDim, selection!!)
                                        selection = null
                                        Logger.i("Copied ${clipboard?.chunks?.size ?: 0} chunks to clipboard")
                                    } finally {
                                        isBatchRunning = false
                                    }
                                }
                            }
                        }
                    )
                    ToolbarButton(
                        icon = Icons.Default.ContentCut,
                        label = "剪切",
                        enabled = selection != null && !isBatchRunning,
                        onClick = {
                            if (selection != null) {
                                if (!hasWritePermission()) { showPermDialog = true; return@ToolbarButton }
                                val sel = selection!!
                                val rect = sel as? SelectionArea.Rectangle
                                scope.launch {
                                    isBatchRunning = true
                                    try {
                                        clipboard = batchProcessor.copyChunks(currentDim, sel)
                                        // Delete after copy
                                        batchProcessor.deleteChunks(currentDim, sel).collect { }
                                        if (rect != null) invalidateRange(currentDim, rect.minChunkX, rect.minChunkZ, rect.maxChunkX, rect.maxChunkZ)
                                        selection = null
                                        Logger.i("Cut ${clipboard?.chunks?.size ?: 0} chunks")
                                    } finally {
                                        isBatchRunning = false
                                    }
                                }
                            }
                        }
                    )
                    ToolbarButton(
                        icon = Icons.Default.ContentPaste,
                        label = "粘贴",
                        enabled = clipboard != null && !isBatchRunning,
                        onClick = {
                            clipboard?.let { clip ->
                                if (!hasWritePermission()) { showPermDialog = true; return@ToolbarButton }
                                // Paste centered on the current view so the user sees it land
                                val originX = viewX.toInt()
                                val originZ = viewZ.toInt()
                                scope.launch {
                                    isBatchRunning = true
                                    try {
                                        batchProcessor.pasteChunks(
                                            currentDim, originX, originZ, clip
                                        ).collect { }
                                        // Invalidate the target footprint so the loader re-reads it.
                                        val w = (clip.chunks.maxOf { it.first.x } - clip.originChunkX)
                                        val h = (clip.chunks.maxOf { it.first.z } - clip.originChunkZ)
                                        invalidateRange(currentDim,
                                            originX, originZ,
                                            originX + w, originZ + h)
                                        clipboard = null
                                        Logger.i("Pasted ${clip.chunks.size} chunks at ($originX,$originZ)")
                                    } finally {
                                        isBatchRunning = false
                                    }
                                }
                            }
                        }
                    )
                    ToolbarButton(
                        icon = if (isSelectMode) Icons.Default.SelectAll else Icons.Default.TouchApp,
                        label = "选择",
                        onClick = {
                            isSelectMode = !isSelectMode
                            if (!isSelectMode) selection = null
                        }
                    )
                    ToolbarButton(
                        icon = if (showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                        label = "网格",
                        onClick = { showGrid = !showGrid }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = errorMessage ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    // God map canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                canvasW = it.size.width
                                canvasH = it.size.height
                            }
                            .pointerInput(isSelectMode) {
                                if (isSelectMode) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            val (cx, cz) = screenToChunk(pos.x, pos.y)
                                            selStartCx = cx
                                            selStartCz = cz
                                            selection = SelectionArea.Rectangle(cx, cz, cx, cz)
                                        },
                                        onDragEnd = { }
                                    ) { change, _ ->
                                        change.consume()
                                        val (cx, cz) = screenToChunk(change.position.x, change.position.y)
                                        selection = SelectionArea.Rectangle(
                                            minOf(selStartCx, cx),
                                            minOf(selStartCz, cz),
                                            maxOf(selStartCx, cx),
                                            maxOf(selStartCz, cz)
                                        )
                                    }
                                } else {
                                    detectTransformGestures { _, pan, zoomChange, _ ->
                                        viewX -= pan.x / (16f * zoom)
                                        viewZ -= pan.y / (16f * zoom)
                                        zoom = (zoom * zoomChange).coerceIn(0.1f, 10f)
                                    }
                                }
                            }
                    ) {
                        val config = GodMapRenderer.RenderConfig(
                            viewX = viewX,
                            viewZ = viewZ,
                            zoom = zoom,
                            showGrid = showGrid,
                            selectionArea = selection
                        )

                        val drawChunks = surfaceCache.values.filter { it.dimension == currentDim }
                        val result = renderer.render(
                            width = size.width.toInt(),
                            height = size.height.toInt(),
                            chunks = drawChunks,
                            config = config
                        )
                        com.zaralynchisel.utils.Logger.i("GodRender: w=${size.width.toInt()} h=${size.height.toInt()} dimChks=${drawChunks.size} withSurface=${drawChunks.size} visible=${result.visibleChunks} view=(${config.viewX.toInt()},${config.viewZ.toInt()}) zoom=${config.zoom}")

                        // Draw the rendered bitmap
                        drawImage(
                            image = result.bitmap.asImageBitmap(),
                            topLeft = Offset.Zero
                        )
                    }

                    // Zoom controls overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SmallFloatingActionButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(10f) }) {
                            Icon(Icons.Default.Add, contentDescription = "放大")
                        }
                        SmallFloatingActionButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(0.1f) }) {
                            Icon(Icons.Default.Remove, contentDescription = "缩小")
                        }
                        SmallFloatingActionButton(onClick = { zoom = 1f; viewX = 0f; viewZ = 0f }) {
                            Icon(Icons.Default.Home, contentDescription = "重置视图")
                        }
                    }

                    // Info overlay
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    ) {
                        Text(
                            text = "区块: ${chunks.size} | 缩放: ${"%.1f".format(zoom)}x",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    // ── Delete confirmation dialog ──────────────────────────────────
    if (showDeleteDialog && selection != null) {
        val sel = selection!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("确认删除") },
            text = { Text("将删除选中区域内的所有区块数据。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        if (!hasWritePermission()) { showPermDialog = true; return@TextButton }
                        scope.launch {
                            isBatchRunning = true
                            try {
                                val rect = sel as? SelectionArea.Rectangle
                                batchProcessor.deleteChunks(currentDim, sel).collect { }
                                if (rect != null) invalidateRange(currentDim, rect.minChunkX, rect.minChunkZ, rect.maxChunkX, rect.maxChunkZ)
                                selection = null
                                Logger.i("Deleted chunks")
                            } finally {
                                isBatchRunning = false
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("需要写权限") },
            text = { Text("修改区块需要“所有文件访问权限”。请在系统设置中为本应用开启后重试。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermDialog = false
                    requestAllFilesAccess()
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showPermDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}