package com.zaralynchisel.ui.settings
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zaralynchisel.ZaralynChiselApp
import com.zaralynchisel.utils.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogViewer: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as ZaralynChiselApp
    val prefs = app.preferenceManager

    var themeMode by remember { mutableStateOf(prefs.themeMode) }
    var renderDistance by remember { mutableIntStateOf(prefs.renderDistance) }
    var batchSize by remember { mutableIntStateOf(prefs.batchSize) }
    var undoLimit by remember { mutableIntStateOf(prefs.undoLimit) }
    var textureCacheMb by remember { mutableIntStateOf(prefs.textureCacheSizeMB) }
    var showThemeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .verticalScroll(rememberScrollState())
        ) {
            // ── Appearance ─────────────────────────────────────────────
            SettingsSectionHeader("外观")

            SettingsClickableItem(
                icon = Icons.Default.DarkMode,
                title = "主题",
                subtitle = when (themeMode) {
                    ThemeMode.LIGHT -> "浅色"
                    ThemeMode.DARK -> "深色"
                    ThemeMode.AUTO -> "自动(系统)"
                },
                onClick = { showThemeDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Performance ────────────────────────────────────────────
            SettingsSectionHeader("性能")

            SettingsSliderItem(
                icon = Icons.Default.Visibility,
                title = "渲染距离",
                subtitle = "$renderDistance 区块",
                value = renderDistance.toFloat(),
                valueRange = 4f..48f,
                onValueChange = { renderDistance = it.toInt(); prefs.renderDistance = it.toInt() }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSliderItem(
                icon = Icons.Default.BatchPrediction,
                title = "批处理大小",
                subtitle = "每批 $batchSize 区块",
                value = batchSize.toFloat(),
                valueRange = 1f..64f,
                onValueChange = { batchSize = it.toInt(); prefs.batchSize = it.toInt() }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSliderItem(
                icon = Icons.Default.Undo,
                title = "撤销上限",
                subtitle = "$undoLimit 步",
                value = undoLimit.toFloat(),
                valueRange = 5f..200f,
                onValueChange = { undoLimit = it.toInt(); prefs.undoLimit = it.toInt() }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Texture ────────────────────────────────────────────────
            SettingsSectionHeader("纹理")

            SettingsSliderItem(
                icon = Icons.Default.Storage,
                title = "纹理缓存",
                subtitle = "${textureCacheMb}MB",
                value = textureCacheMb.toFloat(),
                valueRange = 64f..2048f,
                steps = 15,
                onValueChange = { textureCacheMb = it.toInt(); prefs.textureCacheSizeMB = it.toInt() }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsClickableItem(
                icon = Icons.Default.DeleteSweep,
                title = "清除纹理缓存",
                subtitle = "删除所有下载的纹理",
                onClick = {
                    File(app.cacheDir, "texture_cache").deleteRecursively()
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsClickableItem(
                icon = Icons.Default.BugReport,
                title = "查看日志",
                subtitle = "应用内日志查看器和日志文件",
                onClick = onOpenLogViewer
            )

            // ── About ──────────────────────────────────────────────────
            SettingsSectionHeader("关于")

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "ZaralynChisel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v0.1.0-alpha",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Minecraft Java 版存档编辑器\n使用 Kotlin + Compose + OpenGL ES 3.0 构建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Theme selection dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("主题") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    themeMode = mode
                                    prefs.themeMode = mode
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = when (mode) {
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                    ThemeMode.AUTO -> "自动(系统)"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsClickableItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SettingsSliderItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}