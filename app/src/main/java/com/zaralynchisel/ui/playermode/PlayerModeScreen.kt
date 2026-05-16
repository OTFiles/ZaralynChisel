package com.zaralynchisel.ui.playermode

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.zaralynchisel.renderengine.PlayerRenderer
import com.zaralynchisel.utils.Logger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerModeScreen(
    worldPath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var textureAvailable by remember { mutableStateOf(true) }  // TODO: Check actual texture availability
    var showHud by remember { mutableStateOf(true) }
    var collisionEnabled by remember { mutableStateOf(true) }
    var showChunkGrid by remember { mutableStateOf(false) }

    // Player state
    var posX by remember { mutableFloatStateOf(0f) }
    var posY by remember { mutableFloatStateOf(64f) }
    var posZ by remember { mutableFloatStateOf(0f) }
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }

    if (!textureAvailable) {
        // Textures missing — force downgrade to God Mode
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Textures Not Available",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Player Mode requires Minecraft textures.\nPlease install a resource pack or enable network download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onBack) {
                Text("Return to God Mode")
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Player Mode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHud = !showHud }) {
                        Icon(
                            if (showHud) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle HUD"
                        )
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
                    IconButton(onClick = { /* TODO: Pick block */ }) {
                        Icon(Icons.Default.Colorize, contentDescription = "Pick Block")
                    }
                    IconButton(onClick = { /* TODO: Open inventory */ }) {
                        Icon(Icons.Default.Inventory2, contentDescription = "Inventory")
                    }
                    IconButton(onClick = { collisionEnabled = !collisionEnabled }) {
                        Icon(
                            if (collisionEnabled) Icons.Default.DirectionsWalk else Icons.Default.Flight,
                            contentDescription = "Toggle Collision"
                        )
                    }
                    IconButton(onClick = { showChunkGrid = !showChunkGrid }) {
                        Icon(
                            if (showChunkGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                            contentDescription = "Chunk Grid"
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // OpenGL view
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        val renderer = PlayerRenderer()
                        renderer.viewConfig.showChunkGrid = showChunkGrid
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        tag = "player_renderer"
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // HUD overlay
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
                            text = "XYZ: ${"%.1f".format(posX)} / ${"%.1f".format(posY)} / ${"%.1f".format(posZ)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "Yaw: ${"%.1f".format(yaw)}° Pitch: ${"%.1f".format(pitch)}°",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "Collision: ${if (collisionEnabled) "ON" else "OFF"}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Crosshair
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                ) {
                    Box(modifier = Modifier.size(4.dp))
                }
            }
        }
    }
}