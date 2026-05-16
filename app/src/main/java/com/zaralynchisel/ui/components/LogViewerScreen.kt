package com.zaralynchisel.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaralynchisel.utils.Logger
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(Logger.getBufferedLogs()) }
    var logFiles by remember { mutableStateOf(Logger.getLogFiles()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    var showFileDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = Logger.getBufferedLogsText()
                        copyToClipboard(context, text, "Logs copied")
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = {
                        Logger.clearBuffer()
                        logs = emptyList()
                    }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear buffer")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row: buffer vs files
            var selectedTab by remember { mutableIntStateOf(0) }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("In-Memory (${logs.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        logFiles = Logger.getLogFiles()
                    },
                    text = { Text("Log Files (${logFiles.size})") }
                )
            }

            when (selectedTab) {
                0 -> InMemoryLogsView(logs = logs)
                1 -> LogFilesView(
                    files = logFiles,
                    onViewFile = { file ->
                        selectedFile = file
                        fileContent = Logger.readLogFile(file)
                        showFileDialog = true
                    }
                )
            }
        }
    }

    // File content dialog
    if (showFileDialog && fileContent != null && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { showFileDialog = false },
            title = { Text(selectedFile!!.name) },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = fileContent ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, fileContent ?: "", "File content copied")
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFileDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun InMemoryLogsView(logs: List<Logger.LogEntry>) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    if (logs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("No logs in buffer", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(logs, key = { it.hashCode() }) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: Logger.LogEntry) {
    val levelColor = when (entry.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> Color(0xFFFFA000)
        "INFO" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = levelColor.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = entry.timestamp,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                color = levelColor,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = entry.level,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LogFilesView(
    files: List<File>,
    onViewFile: (File) -> Unit
) {
    if (files.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.FolderOff,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("No log files", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(files, key = { it.absolutePath }) { file ->
                Surface(
                    onClick = { onViewFile(file) },
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${file.length() / 1024}KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "View",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("ZaralynChisel Logs", text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}