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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zaralynchisel.editioncore.*
import com.zaralynchisel.fileaccess.WorldSelector
import com.zaralynchisel.fileaccess.WorldCache
import com.zaralynchisel.renderengine.GodMapRenderer
import com.zaralynchisel.ZaralynChiselApp
import com.zaralynchisel.utils.Logger
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
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // View state
    var viewX by remember { mutableFloatStateOf(0f) }
    var viewZ by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var showGrid by remember { mutableStateOf(true) }
    var isSelectMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<SelectionArea?>(null) }
    // Drag selection state
    var selStartX by remember { mutableFloatStateOf(0f) }
    var selStartZ by remember { mutableFloatStateOf(0f) }

    // Batch operation state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isBatchRunning by remember { mutableStateOf(false) }
    var clipboard by remember { mutableStateOf<ChunkClipboard?>(null) }
    val batchProcessor = remember(worldPath) { BlockBatchProcessor(worldPath) }

    // Current dimension info
    val currentDim by remember(worldData) {
        derivedStateOf {
            worldData?.dimensionPaths?.keys?.firstOrNull() ?: DimensionType.OVERWORLD
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
                                scope.launch {
                                    isBatchRunning = true
                                    try {
                                        clipboard = batchProcessor.copyChunks(currentDim, selection!!)
                                        // Delete after copy
                                        batchProcessor.deleteChunks(currentDim, selection!!).collect { }
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
                                scope.launch {
                                    isBatchRunning = true
                                    try {
                                        val originX = chunks.minOfOrNull { it.x } ?: 0
                                        val originZ = chunks.minOfOrNull { it.z } ?: 0
                                        batchProcessor.pasteChunks(
                                            currentDim, originX, originZ, clip
                                        ).collect { }
                                        clipboard = null
                                        Logger.i("Pasted ${clip.chunks.size} chunks")
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
                            .pointerInput(isSelectMode) {
                                if (isSelectMode) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            val scaled = 16f * zoom
                                            selStartX = (pos.x / scaled).toInt().toFloat()
                                            selStartZ = (pos.y / scaled).toInt().toFloat()
                                        },
                                        onDragEnd = { }
                                    ) { change, _ ->
                                        change.consume()
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

                        val result = renderer.render(
                            width = size.width.toInt(),
                            height = size.height.toInt(),
                            chunks = chunks,
                            config = config
                        )

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
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("确认删除") },
            text = { Text("将删除选中区域内的所有区块数据。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            isBatchRunning = true
                            try {
                                batchProcessor.deleteChunks(currentDim, selection!!)
                                    .collect { /* progress */ }
                                selection = null
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