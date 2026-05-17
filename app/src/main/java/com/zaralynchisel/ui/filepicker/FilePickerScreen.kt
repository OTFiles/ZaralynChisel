package com.zaralynchisel.ui.filepicker
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import com.zaralynchisel.utils.Logger
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerScreen(
    onWorldSelected: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val worldSelector = remember { WorldSelector(context) }
    

    var recentWorlds by remember { mutableStateOf(worldSelector.getRecentWorlds()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // SAF launcher for selecting a world folder
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                errorMessage = null
                try {
                    // Take persistable permission
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )

                    val resolvedPath = worldSelector.resolveSafUri(uri)
                    if (resolvedPath != null) {
                        val validation = worldSelector.validateWorld(resolvedPath)
                        when (validation) {
                            is WorldSelector.ValidationResult.Valid -> {
                                worldSelector.rememberWorld(resolvedPath)
                                recentWorlds = worldSelector.getRecentWorlds()
                                onWorldSelected(resolvedPath)
                            }
                            is WorldSelector.ValidationResult.Invalid -> {
                                errorMessage = validation.reason
                            }
                        }
                    } else {
                        errorMessage = "Could not resolve folder path"
                    }
                } catch (e: Exception) {
                    Logger.e("SAF picker error", e)
                    errorMessage = "Error: ${e.message}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select World") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Text("Browse with System File Picker", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Manual path entry
            OutlinedButton(
                onClick = { /* TODO: Show manual path input dialog */ },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Enter Path Manually")
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
                    text = "Recent Worlds",
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
                                                onWorldSelected(worldPath)
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
                // Empty state
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
                        text = "No recent worlds",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Use the system file picker to navigate\nto your Minecraft saves folder",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
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
                    contentDescription = "Remove from recent",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}