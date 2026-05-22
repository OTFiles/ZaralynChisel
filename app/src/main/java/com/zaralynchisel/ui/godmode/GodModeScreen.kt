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
import com.zaralynchisel.renderengine.GodMapRenderer
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
    val worldSelector = remember { WorldSelector(context) }
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
    var selection by remember { mutableStateOf<SelectionArea?>(null) }

    // Load world data
    LaunchedEffect(worldPath) {
        isLoading = true
        try {
            val info = worldSelector.loadWorldInfo(worldPath)
            if (info != null) {
                worldData = info
                // Scan actual chunks from region files
                val allChunks = mutableListOf<ChunkInfo>()
                for (dim in info.dimensionPaths.keys) {
                    val dimPath = info.dimensionPaths[dim] ?: continue
                    Logger.i("Scanning chunks in $dim at $dimPath")
                    val scanned = worldSelector.scanChunks(dimPath)
                    allChunks.addAll(scanned)
                }
                chunks = allChunks.ifEmpty {
                    // Fallback: at least show spawn area
                    listOf(
                        ChunkInfo(0, 0, DimensionType.OVERWORLD, blockCount = 0),
                        ChunkInfo(1, 0, DimensionType.OVERWORLD, blockCount = 0),
                        ChunkInfo(0, 1, DimensionType.OVERWORLD, blockCount = 0),
                        ChunkInfo(1, 1, DimensionType.OVERWORLD, blockCount = 0)
                    )
                }
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
                        onClick = { /* TODO: Show delete dialog */ }
                    )
                    ToolbarButton(
                        icon = Icons.Default.ContentCopy,
                        label = "复制",
                        onClick = { /* TODO: Copy selection */ }
                    )
                    ToolbarButton(
                        icon = Icons.Default.ContentCut,
                        label = "剪切",
                        onClick = { /* TODO: Cut selection */ }
                    )
                    ToolbarButton(
                        icon = Icons.Default.ContentPaste,
                        label = "粘贴",
                        onClick = { /* TODO: Paste */ }
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
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoomChange, _ ->
                                    viewX -= pan.x / (16f * zoom)
                                    viewZ -= pan.y / (16f * zoom)
                                    zoom = (zoom * zoomChange).coerceIn(0.1f, 10f)
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
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}