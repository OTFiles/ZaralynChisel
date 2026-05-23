package com.zaralynchisel.ui.filepicker
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaralynchisel.fileaccess.WorldSelector
import com.zaralynchisel.fileaccess.SafFileAccess
import com.zaralynchisel.utils.Logger
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    onOpenGodMode: (String) -> Unit,
    onOpenPlayerMode: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val worldSelector = remember { WorldSelector(context) }
    val safAccess = remember { SafFileAccess(context) }

    var recentWorlds by remember { mutableStateOf(worldSelector.getRecentWorlds()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Mode picker state
    var pendingWorldPath by remember { mutableStateOf<String?>(null) }
    var showModePicker by remember { mutableStateOf(false) }

    // Manual path dialog state
    var showManualPathDialog by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }

    // After world is validated, show mode picker
    fun onWorldValidated(path: String) {
        pendingWorldPath = path
        showModePicker = true
    }

    // SAF launcher
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                errorMessage = null
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    safAccess.setTreeUri(uri)
                    worldSelector.safAccess = safAccess
                    val resolvedPath = worldSelector.resolveSafUri(uri)
                    if (resolvedPath != null) {
                        val validation = worldSelector.validateWorld(resolvedPath)
                        when (validation) {
                            is WorldSelector.ValidationResult.Valid -> {
                                worldSelector.rememberWorld(resolvedPath)
                                recentWorlds = worldSelector.getRecentWorlds()
                                onWorldValidated(resolvedPath)
                            }
                            is WorldSelector.ValidationResult.Invalid -> {
                                errorMessage = validation.reason
                            }
                        }
                    } else {
                        errorMessage = "无法解析文件夹路径"
                    }
                } catch (e: Exception) {
                    Logger.e("SAF picker error", e)
                    errorMessage = "错误: ${e.message}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择世界") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // SAF button
            Button(
                onClick = { safLauncher.launch(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("使用系统文件选择器", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Manual path entry
            OutlinedButton(
                onClick = { showManualPathDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("手动输入路径")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Error message
            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Loading indicator
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Recent worlds
            if (recentWorlds.isNotEmpty()) {
                Text(
                    text = "最近打开的世界",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentWorlds) { worldPath ->
                        RecentWorldCard(
                            worldPath = worldPath,
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    try {
                                        val validation = worldSelector.validateWorld(worldPath)
                                        when (validation) {
                                            is WorldSelector.ValidationResult.Valid -> {
                                                onWorldValidated(worldPath)
                                            }
                                            is WorldSelector.ValidationResult.Invalid -> {
                                                errorMessage = validation.reason
                                                worldSelector.forgetWorld(worldPath)
                                                recentWorlds = worldSelector.getRecentWorlds()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = e.message
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            onRemove = {
                                worldSelector.forgetWorld(worldPath)
                                recentWorlds = worldSelector.getRecentWorlds()
                            }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "没有最近的世界",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "使用系统文件选择器导航到\n您的 Minecraft 存档文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    // ── Mode selection dialog ─────────────────────────────────────────
    if (showModePicker && pendingWorldPath != null) {
        val path = pendingWorldPath!!
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            icon = {
                Icon(Icons.Default.Map, contentDescription = null)
            },
            title = { Text("选择编辑模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "世界: ${File(path).name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            showModePicker = false
                            onOpenGodMode(path)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("创造模式 - 2D 地图概览编辑")
                    }

                    OutlinedButton(
                        onClick = {
                            showModePicker = false
                            onOpenPlayerMode(path)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.VideogameAsset, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("游戏模式 - 第一人称编辑")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModePicker = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ── Manual path entry dialog ──────────────────────────────────────
    if (showManualPathDialog) {
        AlertDialog(
            onDismissRequest = { showManualPathDialog = false },
            title = { Text("手动输入路径") },
            text = {
                Column {
                    Text(
                        text = "输入 Minecraft 存档文件夹的完整路径\n例如: /storage/emulated/0/games/com.mojang/minecraftWorlds/MyWorld",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        label = { Text("路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showManualPathDialog = false
                        if (manualPath.isNotBlank()) {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val validation = worldSelector.validateWorld(manualPath)
                                    when (validation) {
                                        is WorldSelector.ValidationResult.Valid -> {
                                            worldSelector.rememberWorld(manualPath)
                                            recentWorlds = worldSelector.getRecentWorlds()
                                            onWorldValidated(manualPath)
                                        }
                                        is WorldSelector.ValidationResult.Invalid -> {
                                            errorMessage = validation.reason
                                        }
                                    }
                                } catch (e: Exception) {
                                    Logger.e("Manual path error", e)
                                    errorMessage = "错误: ${e.message}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualPathDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun RecentWorldCard(
    worldPath: String,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val worldName = File(worldPath).name
    val parentPath = File(worldPath).parent

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = worldName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = parentPath ?: worldPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "从最近列表移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}