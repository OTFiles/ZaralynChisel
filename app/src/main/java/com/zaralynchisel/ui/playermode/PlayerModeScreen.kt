package com.zaralynchisel.ui.playermode

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zaralynchisel.renderengine.PlayerRenderer
import com.zaralynchisel.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerModeScreen(
    worldPath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val hasKeyboard = configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY

    val app = context.applicationContext as com.zaralynchisel.ZaralynChiselApp
    val worldSelector = remember {
        com.zaralynchisel.fileaccess.WorldSelector(context).also { it.safAccess = app.safAccess }
    }

    // The GL renderer instance (set when the AndroidView factory runs). Used by
    // the movement loop for ground-height collision queries.
    var rendererRef by remember { mutableStateOf<PlayerRenderer?>(null) }

    // Texture resolution: reads from the world's .minecraft assets (index + objects),
    // falls back to mod jars, then network download; failures are counted and shown.
    val textureResolver = remember {
        com.zaralynchisel.fileaccess.TextureResolver(context)
    }
    var textureErrors by remember { mutableIntStateOf(0) }
    var fov by remember { mutableFloatStateOf(70f) }

    var showHud by remember { mutableStateOf(true) }
    var collisionEnabled by remember { mutableStateOf(true) }
    var showChunkGrid by remember { mutableStateOf(false) }
    var showWASD by remember { mutableStateOf(true) }

    // Player state — positioned at their last real position (level.dat Player.Pos)
    // once the world info is read, falling back to world spawn. Y comes from the
    // saved Y when plausible, otherwise from the column's surface heightmap.
    var posX by remember { mutableFloatStateOf(0f) }
    var posY by remember { mutableFloatStateOf(80f) }
    var posZ by remember { mutableFloatStateOf(0f) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }

    // Resolve the spawn point. The player's last position beats world spawn
    // (spawn can sit in ungenerated terrain), and the ground height under that
    // spot beats a fixed Y (82 can be underground on mountains or in the sky
    // over oceans).
    LaunchedEffect(worldPath) {
        val info = worldSelector.loadWorldInfo(worldPath)
        val pX = info?.playerX?.toFloat() ?: (info?.spawnX?.toFloat() ?: 0f)
        val pZ = info?.playerZ?.toFloat() ?: (info?.spawnZ?.toFloat() ?: 0f)
        posX = pX + 0.5f
        posZ = pZ + 0.5f
        val savedY = info?.playerY
        val savedYPlausible = savedY != null && savedY > -60.0 && savedY < 320.0
        if (savedYPlausible) {
            posY = savedY.toFloat() + 1.5f
            Logger.i("Player spawn: player pos ($pX, $pZ) y=${savedY}")
        } else {
            // No usable saved Y — stand on the surface under the spawn column.
            val chunkX = kotlin.math.floor(pX / 16f).toInt()
            val chunkZ = kotlin.math.floor(pZ / 16f).toInt()
            val data = worldSelector.loadChunkSurfaceAndHeight(
                worldPath, chunkX, chunkZ, com.zaralynchisel.editioncore.DimensionType.OVERWORLD
            )
            val ground = data?.heights?.get(Math.floorMod(pX.toInt(), 16))?.get(Math.floorMod(pZ.toInt(), 16))
            posY = if (ground != null && ground != Int.MIN_VALUE) ground + 1.5f else 82f
            Logger.i("Player spawn: fallback ($pX, $pZ) ground=${ground ?: "none"}")
        }

        // Initialise texture resolution and hand it to the renderer so chunks get
        // real tiles (not just the placeholder).
        textureResolver.initialize(worldPath)
        textureResolver.setVersion(info?.gameVersion ?: "1.21")
        rendererRef?.setTextureResolver(textureResolver)
    }

    // Poll the renderer's texture-error counter into Compose state for the HUD.
    LaunchedEffect(Unit) {
        while (isActive) {
            textureErrors = rendererRef?.textureErrorCount ?: 0
            delay(500L)
        }
    }

    // Continuous movement via WASD (held down = repeated move)
    var moveForward by remember { mutableStateOf(false) }
    var moveBack by remember { mutableStateOf(false) }
    var moveLeft by remember { mutableStateOf(false) }
    var moveRight by remember { mutableStateOf(false) }
    var moveUp by remember { mutableStateOf(false) }
    var moveDown by remember { mutableStateOf(false) }

    // Continuous movement coroutine. With collision enabled the player is glued
    // to the terrain: moving uphill raises them, walking off a cliff makes them
    // fall, ↑ jumps and ↓ digs down (release ↓ to pop back to the surface).
    // Without collision it's free flight (keys ±Y directly, no gravity).
    LaunchedEffect(moveForward, moveBack, moveLeft, moveRight, moveUp, moveDown) {
        val moveSpeed = 0.3f
        while (isActive) {
            if (moveForward || moveBack || moveLeft || moveRight) {
                val radYaw = Math.toRadians(yaw.toDouble())
                val sinYaw = kotlin.math.sin(radYaw).toFloat()
                val cosYaw = kotlin.math.cos(radYaw).toFloat()

                if (moveForward) { posX -= sinYaw * moveSpeed; posZ += cosYaw * moveSpeed }
                if (moveBack) { posX += sinYaw * moveSpeed; posZ -= cosYaw * moveSpeed }
                if (moveLeft) { posX -= cosYaw * moveSpeed; posZ -= sinYaw * moveSpeed }
                if (moveRight) { posX += cosYaw * moveSpeed; posZ += sinYaw * moveSpeed }
            }
            if (collisionEnabled) {
                val ground = rendererRef?.groundHeightAt(posX, posZ)
                if (ground != null) {
                    val floor = ground + 1.62f // eye height
                    when {
                        moveUp -> posY += 0.5f    // jump / climb
                        moveDown -> posY -= moveSpeed // dig down (hold to stay under)
                        else -> { posY -= 0.25f; if (posY < floor) posY = floor } // gravity
                    }
                    if (posY < floor && !moveDown) posY = floor
                }
            } else {
                if (moveUp) posY += moveSpeed
                if (moveDown) posY -= moveSpeed
            }
            delay(16L) // ~60 FPS
        }
    }


    Scaffold(
        topBar = {
            // Compact top bar
            TopAppBar(
                title = {
                    Text("Player 模式", fontSize = 16.sp)
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                             modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    // Keyboard indicator
                    if (hasKeyboard) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = "键盘已连接",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = { showHud = !showHud },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            if (showHud) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "切换 HUD",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { showWASD = !showWASD },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Gamepad,
                            contentDescription = "切换触屏控件",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // FOV cycle: 70 (default) → 60 → 50
                    TextButton(
                        onClick = {
                            fov = when {
                                fov > 65f -> 60f
                                fov > 55f -> 50f
                                else -> 70f
                            }
                            rendererRef?.updateFov(fov)
                        },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("FOV${fov.toInt()}", fontSize = 12.sp)
                    }
                },
                modifier = Modifier.height(44.dp)
            )
        },
        bottomBar = {
            // Compact bottom bar
            BottomAppBar(
                modifier = Modifier.height(44.dp),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { /* TODO: Pick block */ },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Colorize, contentDescription = "选取方块",
                             modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { /* TODO: Open inventory */ },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Inventory2, contentDescription = "背包",
                             modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { collisionEnabled = !collisionEnabled },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (collisionEnabled) Icons.Default.DirectionsWalk else Icons.Default.Flight,
                            contentDescription = "切换碰撞",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { showChunkGrid = !showChunkGrid },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (showChunkGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                            contentDescription = "区块网格",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Keep a reference so the GL context can be torn down when leaving.
            var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }

            // On exit: stop the GL thread, cancel the loader scope and delete GL
            // resources on the GL thread (queueEvent) so nothing leaks or crashes.
            DisposableEffect(Unit) {
                onDispose {
                    glViewRef?.let { v ->
                        (v.tag as? PlayerRenderer)?.let { r ->
                            v.queueEvent { r.cleanup() }
                        }
                        v.onPause()
                    }
                    textureResolver.close()
                }
            }

            // OpenGL view with touch gesture handling
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        // RGBA8888 + 24-bit depth (default is RGB565/16-bit).
                        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
                        val renderer = PlayerRenderer(
                            worldPath = worldPath,
                            spawnX = posX.toInt(),
                            spawnY = posY.toInt(),
                            spawnZ = posZ.toInt(),
                            dimension = com.zaralynchisel.editioncore.DimensionType.OVERWORLD,
                            worldSelector = worldSelector
                        )
                        rendererRef = renderer
                        renderer.viewConfig.showChunkGrid = showChunkGrid
                        renderer.updateCamera(posX, posY, posZ, yaw, pitch)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        tag = renderer

                        var lastX = 0f
                        var lastY = 0f
                        var isMultiTouch = false
                        setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    lastX = event.x
                                    lastY = event.y
                                    isMultiTouch = false
                                    true
                                }
                                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                                    isMultiTouch = true
                                    true
                                }
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (!isMultiTouch && event.pointerCount == 1) {
                                        val dx = event.x - lastX
                                        val dy = event.y - lastY
                                        yaw = (yaw + dx * 0.15f) % 360f
                                        pitch = (pitch - dy * 0.15f).coerceIn(-89f, 89f)
                                        renderer.updateCamera(posX, posY, posZ, yaw, pitch)
                                        lastX = event.x
                                        lastY = event.y
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                },
                update = { glView ->
                    glViewRef = glView
                    (glView.tag as? PlayerRenderer)?.let { r ->
                        r.viewConfig.showChunkGrid = showChunkGrid
                        r.updateCamera(posX, posY, posZ, yaw, pitch)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── WASD Touch Controls ──────────────────────────────────
            if (showWASD) {
                // Left side — D-Pad (WASD)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // W / Forward
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        WasdButton("W", pressed = moveForward) { moveForward = it }
                    }
                    // A S D
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        WasdButton("A", pressed = moveLeft) { moveLeft = it }
                        WasdButton("S", pressed = moveBack) { moveBack = it }
                        WasdButton("D", pressed = moveRight) { moveRight = it }
                    }
                }

                // Right side — Jump / Crouch
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WasdButton("↑", pressed = moveUp, size = 44.dp) { moveUp = it }
                    WasdButton("↓", pressed = moveDown, size = 44.dp) { moveDown = it }
                }
            }

            // ── HUD Overlay ───────────────────────────────────────────
            if (showHud) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "坐标: ${"%.1f".format(posX)} / ${"%.1f".format(posY)} / ${"%.1f".format(posZ)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "偏航: ${"%.1f".format(yaw)}°",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "碰撞: ${if (collisionEnabled) "开" else "关"}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (textureErrors > 0) {
                            Text(
                                text = "纹理加载失败: $textureErrors 个 (详见日志)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Crosshair
                Box(
                    modifier = Modifier.align(Alignment.Center).size(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.6f))
                )
            }
        }
    }
}

/**
 * Touch button for WASD movement.
 * Supports press-and-hold continuous movement.
 */
@Composable
private fun WasdButton(
    label: String,
    pressed: Boolean,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    onPress: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        tonalElevation = if (pressed) 2.dp else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                androidx.compose.ui.input.pointer.PointerEventType.Press -> onPress(true)
                                androidx.compose.ui.input.pointer.PointerEventType.Release -> onPress(false)
                                else -> {}
                            }
                        }
                    }
                }
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (pressed) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}