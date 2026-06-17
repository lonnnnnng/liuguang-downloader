package com.liuguang.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuguang.downloader.data.download.DownloadTaskState
import com.liuguang.downloader.ui.DownloadTaskUi
import com.liuguang.downloader.ui.DownloaderUiState
import com.liuguang.downloader.ui.DownloaderViewModel
import com.liuguang.downloader.ui.isM3u8Url
import com.liuguang.downloader.ui.theme.LiuguangDownloaderTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    private var latestLaunchPayload by mutableStateOf<DownloadLaunchPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latestLaunchPayload = intent?.downloadLaunchPayload()
        setContent {
            LiuguangDownloaderTheme {
                DownloaderApp(
                    launchPayload = latestLaunchPayload,
                    onLaunchPayloadConsumed = { latestLaunchPayload = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        latestLaunchPayload = intent.downloadLaunchPayload()
    }
}

@Composable
private fun DownloaderApp(
    launchPayload: DownloadLaunchPayload?,
    onLaunchPayloadConsumed: () -> Unit,
    viewModel: DownloaderViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedScreen by remember { mutableStateOf(AppScreen.Download) }
    var openAddTaskDialogSignal by remember { mutableStateOf<Long?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            viewModel.setCustomDirectory(uri)
        }
    }

    LaunchedEffect(launchPayload?.requestId) {
        if (launchPayload != null) {
            viewModel.setDownloadDraft(
                url = launchPayload.url,
                fileName = launchPayload.fileName.orEmpty()
            )
            selectedScreen = AppScreen.Download
            openAddTaskDialogSignal = launchPayload.requestId
            onLaunchPayloadConsumed()
        }
    }

    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 14.dp)
                    .padding(top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppHeader(selectedScreen)

                when (selectedScreen) {
                    AppScreen.Download -> DownloadScreen(
                        state = state,
                        openAddTaskDialogSignal = openAddTaskDialogSignal,
                        onAddTaskDialogSignalConsumed = { openAddTaskDialogSignal = null },
                        onUrlChange = viewModel::updateUrl,
                        onFileNameChange = viewModel::updateFileName,
                        onReadClipboard = viewModel::refreshClipboard,
                        onRefreshStorageInfo = viewModel::refreshStorageInfo,
                        onCreateTask = viewModel::startDownload,
                        onStartTask = viewModel::startTask,
                        onPauseTask = viewModel::pauseTask,
                        onCopyTaskUrl = viewModel::copyTaskUrl,
                        onOpenTask = viewModel::openTask,
                        onDeleteTask = viewModel::deleteTask,
                        onRestartTask = viewModel::restartTask
                    )
                    AppScreen.Settings -> SettingsScreen(
                        state = state,
                        onChooseDirectory = { directoryLauncher.launch(null) },
                        onResetDirectory = viewModel::resetDirectory,
                        onMaxParallelChange = viewModel::setMaxParallelTasks,
                        onDownloadThreadChange = viewModel::setDownloadThreadCount
                    )
                }
            }

            AppBottomBar(
                selectedScreen = selectedScreen,
                onSelectScreen = { selectedScreen = it }
            )
        }
        }
    }
}

private enum class AppScreen {
    Download,
    Settings
}

private data class DownloadLaunchPayload(
    val url: String,
    val fileName: String?,
    val requestId: Long = System.nanoTime()
)

private fun Intent.downloadLaunchPayload(): DownloadLaunchPayload? {
    val deepLinkUrl = data
        ?.takeIf { it.scheme == "liuguangdl" && it.host == "download" }
        ?.getQueryParameter("url")
    val deepLinkFileName = data
        ?.takeIf { it.scheme == "liuguangdl" && it.host == "download" }
        ?.let { uri ->
            uri.getQueryParameter("title")
                ?: uri.getQueryParameter("name")
                ?: uri.getQueryParameter("fileName")
        }
    val candidates = listOfNotNull(
        deepLinkUrl,
        getStringExtra(EXTRA_M3U8_URL),
        getStringExtra(Intent.EXTRA_TEXT),
        dataString
    )
    val url = candidates
        .map(String::trim)
        .firstOrNull(::isM3u8Url)
        ?: return null
    val fileName = listOfNotNull(
        deepLinkFileName,
        getStringExtra(EXTRA_FILE_NAME),
        getStringExtra(Intent.EXTRA_TITLE)
    )
        .map(String::trim)
        .firstOrNull { it.isNotBlank() }
    return DownloadLaunchPayload(url = url, fileName = fileName)
}

private const val EXTRA_M3U8_URL = "com.liuguang.downloader.extra.M3U8_URL"
private const val EXTRA_FILE_NAME = "com.liuguang.downloader.extra.FILE_NAME"

@Composable
private fun AppHeader(selectedScreen: AppScreen) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.76f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                shape = RectangleShape
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = when (selectedScreen) {
                AppScreen.Download -> "下载"
                AppScreen.Settings -> "设置"
            },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DownloadScreen(
    state: DownloaderUiState,
    openAddTaskDialogSignal: Long?,
    onAddTaskDialogSignalConsumed: () -> Unit,
    onUrlChange: (String) -> Unit,
    onFileNameChange: (String) -> Unit,
    onReadClipboard: () -> Unit,
    onRefreshStorageInfo: () -> Unit,
    onCreateTask: () -> Unit,
    onStartTask: (DownloadTaskUi) -> Unit,
    onPauseTask: (DownloadTaskUi) -> Unit,
    onCopyTaskUrl: (DownloadTaskUi) -> Unit,
    onOpenTask: (DownloadTaskUi) -> Unit,
    onDeleteTask: (DownloadTaskUi) -> Unit,
    onRestartTask: (DownloadTaskUi) -> Unit
) {
    var selectedFilter by remember { mutableStateOf(TaskFilter.All) }
    var showAddTaskDialog by remember { mutableStateOf(false) }
    val filteredTasks = remember(state.tasks, selectedFilter) {
        state.tasks.filter(selectedFilter::matches)
    }

    LaunchedEffect(openAddTaskDialogSignal) {
        if (openAddTaskDialogSignal != null) {
            showAddTaskDialog = true
            onAddTaskDialogSignalConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DownloadStatusTabs(
                selectedFilter = selectedFilter,
                tasks = state.tasks,
                onFilterSelected = { selectedFilter = it }
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                contentPadding = PaddingValues(bottom = 68.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onStartTask = onStartTask,
                        onPauseTask = onPauseTask,
                        onCopyTaskUrl = onCopyTaskUrl,
                        onOpenTask = onOpenTask,
                        onDeleteTask = onDeleteTask,
                        onRestartTask = onRestartTask
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                onUrlChange("")
                onFileNameChange("")
                onRefreshStorageInfo()
                showAddTaskDialog = true
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "新建下载任务",
                modifier = Modifier.size(26.dp)
            )
        }
    }

    if (showAddTaskDialog) {
        AddTaskDialog(
            state = state,
            onUrlChange = onUrlChange,
            onFileNameChange = onFileNameChange,
            onReadClipboard = onReadClipboard,
            onDismiss = { showAddTaskDialog = false },
            onCreateTask = {
                onCreateTask()
                showAddTaskDialog = false
            }
        )
    }
}

@Composable
private fun AddTaskDialog(
    state: DownloaderUiState,
    onUrlChange: (String) -> Unit,
    onFileNameChange: (String) -> Unit,
    onReadClipboard: () -> Unit,
    onDismiss: () -> Unit,
    onCreateTask: () -> Unit
) {
    val valid = isM3u8Url(state.url)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 360.dp),
            shape = RectangleShape,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "新建下载任务",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(
                        onClick = onReadClipboard,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("剪贴板", fontSize = 11.sp)
                    }
                }

                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChange,
                    label = { Text("m3u8 地址", fontSize = 11.sp) },
                    singleLine = false,
                    minLines = 5,
                    maxLines = 5,
                    shape = RectangleShape,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.fileName,
                    onValueChange = onFileNameChange,
                    label = { Text("文件名", fontSize = 11.sp) },
                    placeholder = { Text("默认使用时间戳", fontSize = 11.sp) },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 3,
                    shape = RectangleShape,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )

                StorageInfoRow(
                    used = state.storageUsedLabel,
                    total = state.storageTotalLabel,
                    available = state.storageAvailableLabel
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RectangleShape,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("取消", fontSize = 11.sp)
                    }
                    Button(
                        onClick = onCreateTask,
                        enabled = valid,
                        shape = RectangleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("确定", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageInfoRow(
    used: String,
    total: String,
    available: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StorageInfoItem(label = "已用", value = used)
        StorageInfoItem(label = "总容量", value = total)
        StorageInfoItem(label = "剩余", value = available)
    }
}

@Composable
private fun StorageInfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 12.sp
        )
        Text(
            text = value.ifBlank { "-" },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private enum class TaskFilter(val label: String) {
    All("全部"),
    Queued("队列中"),
    Running("下载中"),
    Completed("已完成"),
    Failed("失败");

    fun matches(task: DownloadTaskUi): Boolean {
        return when (this) {
            All -> true
            Queued -> task.state == DownloadTaskState.Queued
                || task.state == DownloadTaskState.Paused
            Running -> task.state == DownloadTaskState.Running
            Completed -> task.state == DownloadTaskState.Completed
            Failed -> task.state == DownloadTaskState.Failed || task.state == DownloadTaskState.Canceled
        }
    }
}

@Composable
private fun DownloadStatusTabs(
    selectedFilter: TaskFilter,
    tasks: List<DownloadTaskUi>,
    onFilterSelected: (TaskFilter) -> Unit
) {
    val filters = TaskFilter.entries
    SurfaceCard(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            filters.forEach { filter ->
                val selected = selectedFilter == filter
                val count = tasks.count(filter::matches)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp)
                        .clip(RectangleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                        .clickable { onFilterSelected(filter) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${filter.label}($count)",
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 9.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: DownloaderUiState,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onMaxParallelChange: (Int) -> Unit,
    onDownloadThreadChange: (Int) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            SettingsPanel(
                label = state.customDirectoryLabel,
                hasCustomDirectory = state.customDirectoryUri != null,
                maxParallelTasks = state.maxParallelTasks,
                downloadThreadCount = state.downloadThreadCount,
                onChooseDirectory = onChooseDirectory,
                onResetDirectory = onResetDirectory,
                onMaxParallelChange = onMaxParallelChange,
                onDownloadThreadChange = onDownloadThreadChange
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    label: String,
    hasCustomDirectory: Boolean,
    maxParallelTasks: Int,
    downloadThreadCount: Int,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onMaxParallelChange: (Int) -> Unit,
    onDownloadThreadChange: (Int) -> Unit
) {
    SurfaceCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingsSectionHeader(
                icon = Icons.Default.Folder,
                title = "保存目录"
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = onChooseDirectory,
                    shape = RectangleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("自定义", fontSize = 10.sp)
                }
                if (hasCustomDirectory) {
                    IconButton(
                        onClick = onResetDirectory,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "恢复默认目录",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            SettingsSectionHeader(
                icon = Icons.Default.Settings,
                title = "并发设置"
            )
            SettingStepper(
                label = "最大并行任务",
                value = maxParallelTasks,
                minValue = 1,
                onValueChange = onMaxParallelChange
            )
            SettingStepper(
                label = "分片下载线程",
                value = downloadThreadCount,
                minValue = 1,
                onValueChange = onDownloadThreadChange
            )

            SettingsSectionHeader(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                title = "下载偏好"
            )
            SettingRow(label = "输出格式", value = "单个 MP4")
            SettingRow(label = "清晰度", value = "自动选择最高")
            SettingRow(label = "任务方式", value = "队列 + 前台服务")

            SettingsSectionHeader(
                icon = Icons.Default.Info,
                title = "兼容范围"
            )
            SettingRow(label = "支持", value = "普通 / AES-128 TS-HLS")
            SettingRow(label = "暂不支持", value = "SAMPLE-AES、fMP4、BYTERANGE")
            SettingRow(label = "请求头", value = "暂不自定义")
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun AppBottomBar(
    selectedScreen: AppScreen,
    onSelectScreen: (AppScreen) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 64.dp, top = 2.dp, end = 64.dp, bottom = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBottomBarItem(
            selected = selectedScreen == AppScreen.Download,
                onClick = { onSelectScreen(AppScreen.Download) },
                icon = {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                },
                label = "下载",
                modifier = Modifier.weight(1f)
            )
            AppBottomBarItem(
            selected = selectedScreen == AppScreen.Settings,
                onClick = { onSelectScreen(AppScreen.Settings) },
                icon = {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                },
                label = "设置",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AppBottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .width(84.dp)
                .fillMaxHeight()
                .background(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RectangleShape
                )
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides contentColor
                ) {
                    icon()
                }
            }
            Text(
                text = label,
                color = contentColor,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 10.sp
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DownloadConcurrencyPanel(
    maxParallelTasks: Int,
    downloadThreadCount: Int,
    onMaxParallelChange: (Int) -> Unit,
    onDownloadThreadChange: (Int) -> Unit
) {
    SurfaceCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "并发设置",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 14.sp
                )
            }
            SettingStepper(
                label = "最大并行任务",
                value = maxParallelTasks,
                minValue = 1,
                onValueChange = onMaxParallelChange
            )
            SettingStepper(
                label = "分片下载线程",
                value = downloadThreadCount,
                minValue = 1,
                onValueChange = onDownloadThreadChange
            )
        }
    }
}

@Composable
private fun SettingStepper(
    label: String,
    value: Int,
    minValue: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 12.sp
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange(value - 1) },
                enabled = value > minValue,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "减少",
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp,
                modifier = Modifier.widthIn(min = 26.dp, max = 64.dp)
            )
            IconButton(
                onClick = { onValueChange(value + 1) },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "增加",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun DownloadInfoPanel() {
    SurfaceCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "下载偏好",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 14.sp
                )
            }
            SettingRow(label = "输出格式", value = "单个 MP4")
            SettingRow(label = "清晰度", value = "自动选择最高")
            SettingRow(label = "任务方式", value = "队列 + 前台服务")
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "兼容范围",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 14.sp
            )
            SettingRow(label = "支持", value = "普通 / AES-128 TS-HLS")
            SettingRow(label = "暂不支持", value = "SAMPLE-AES、fMP4、BYTERANGE")
            SettingRow(label = "请求头", value = "暂不自定义")
        }
    }
}

@Composable
private fun SupportScopePanel() {
    SurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "兼容范围",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            SettingRow(label = "支持", value = "普通 / AES-128 TS-HLS")
            SettingRow(label = "暂不支持", value = "SAMPLE-AES、fMP4、BYTERANGE")
            SettingRow(label = "请求头", value = "暂不自定义")
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 12.sp
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun DownloadDirectoryPanel(
    label: String,
    hasCustomDirectory: Boolean,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit
) {
    SurfaceCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "保存目录",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    onClick = onChooseDirectory,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("自定义", fontSize = 10.sp)
                }
                if (hasCustomDirectory) {
                    IconButton(
                        onClick = onResetDirectory,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "恢复默认目录",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTaskCard(filter: TaskFilter) {
    SurfaceCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = when (filter) {
                    TaskFilter.All -> "这里还没有下载任务"
                    TaskFilter.Queued -> "当前没有排队中的任务"
                    TaskFilter.Running -> "当前没有正在下载的任务"
                    TaskFilter.Completed -> "当前没有已完成任务"
                    TaskFilter.Failed -> "当前没有失败或已取消任务"
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when (filter) {
                    TaskFilter.All -> "点右下角 + 添加 m3u8 链接，系统会自动识别并开始入队。"
                    TaskFilter.Queued -> "新任务加入后会在这里等待开始。"
                    TaskFilter.Running -> "下载开始后会在这里显示分片、速度和用时。"
                    TaskFilter.Completed -> "合并后的 MP4 会保存在默认目录或你指定的位置。"
                    TaskFilter.Failed -> "失败任务支持重新下载、复制链接和查看详情。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .width(64.dp)
                    .height(4.dp)
                    .clip(RectangleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    task: DownloadTaskUi,
    onStartTask: (DownloadTaskUi) -> Unit,
    onPauseTask: (DownloadTaskUi) -> Unit,
    onCopyTaskUrl: (DownloadTaskUi) -> Unit,
    onOpenTask: (DownloadTaskUi) -> Unit,
    onDeleteTask: (DownloadTaskUi) -> Unit,
    onRestartTask: (DownloadTaskUi) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Box {
        SurfaceCard(
            modifier = Modifier.combinedClickable(
                onClick = { showDetails = true },
                onLongClick = { menuExpanded = true }
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = task.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = task.status,
                        color = when (task.state) {
                            DownloadTaskState.Failed -> MaterialTheme.colorScheme.error
                            DownloadTaskState.Canceled,
                            DownloadTaskState.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
                            DownloadTaskState.Completed -> MaterialTheme.colorScheme.primary
                            DownloadTaskState.Queued,
                            DownloadTaskState.Running -> MaterialTheme.colorScheme.primary
                        },
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = task.url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                when {
                    task.state == DownloadTaskState.Running && task.totalSegments > 0 -> {
                        DownloadStatsRow(task = task)
                    }
                    task.state == DownloadTaskState.Completed && task.totalSegments > 0 -> {
                        CompletedStatsRow(task = task)
                        Text(
                            text = task.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    else -> {
                        Text(
                            text = task.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        TaskActionMenu(
            task = task,
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onStartTask = onStartTask,
            onPauseTask = onPauseTask,
            onShowDetails = { showDetails = true },
            onCopyTaskUrl = onCopyTaskUrl,
            onOpenTask = onOpenTask,
            onDeleteTask = onDeleteTask,
            onRestartTask = onRestartTask
        )
    }

    if (showDetails) {
        TaskDetailsDialog(
            task = task,
            onDismiss = { showDetails = false },
            onStartTask = onStartTask,
            onPauseTask = onPauseTask,
            onCopyTaskUrl = onCopyTaskUrl,
            onOpenTask = onOpenTask
        )
    }
}

@Composable
private fun DownloadStatsRow(task: DownloadTaskUi) {
    val progressLabel = if (task.status == "合并 MP4") "合并" else "分片"
    val speedValue = if (task.status == "合并 MP4" || task.speedBytesPerSecond <= 0L) {
        "-"
    } else {
        formatSpeed(task.speedBytesPerSecond)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DownloadStatItem(
            label = progressLabel,
            value = "${task.completedSegments}/${task.totalSegments}",
            modifier = Modifier.weight(1f)
        )
        DownloadStatItem(
            label = "大小",
            value = formatBytes(task.downloadedBytes),
            modifier = Modifier.weight(1f)
        )
        DownloadStatItem(
            label = "速度",
            value = speedValue,
            modifier = Modifier.weight(1f)
        )
        DownloadStatItem(
            label = "用时",
            value = formatDuration(task.elapsedMillis),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CompletedStatsRow(task: DownloadTaskUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DownloadStatItem(
            label = "分片",
            value = "${task.completedSegments}/${task.totalSegments}",
            modifier = Modifier.weight(1f)
        )
        DownloadStatItem(
            label = "大小",
            value = formatBytes(task.downloadedBytes),
            modifier = Modifier.weight(1f)
        )
        DownloadStatItem(
            label = "总用时",
            value = formatDuration(task.elapsedMillis),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DownloadStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = "$label $value",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        textAlign = TextAlign.Start,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun TaskActionMenu(
    task: DownloadTaskUi,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onStartTask: (DownloadTaskUi) -> Unit,
    onPauseTask: (DownloadTaskUi) -> Unit,
    onShowDetails: () -> Unit,
    onCopyTaskUrl: (DownloadTaskUi) -> Unit,
    onOpenTask: (DownloadTaskUi) -> Unit,
    onDeleteTask: (DownloadTaskUi) -> Unit,
    onRestartTask: (DownloadTaskUi) -> Unit
) {
    val canStart = task.canStart()
    val canPause = task.canPause()
    val canOpen = task.state == DownloadTaskState.Completed && task.outputUri != null

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (canStart) {
            DropdownMenuItem(
                text = { Text(task.startActionLabel(), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onStartTask(task)
                }
            )
        }
        if (canPause) {
            DropdownMenuItem(
                text = { Text("暂停", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onPauseTask(task)
                }
            )
        }
        DropdownMenuItem(
            text = { Text("查看详情", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            onClick = {
                onDismiss()
                onShowDetails()
            }
        )
        DropdownMenuItem(
            text = { Text("复制下载链接", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = {
                onDismiss()
                onCopyTaskUrl(task)
            }
        )
        if (canOpen) {
            DropdownMenuItem(
                text = { Text("打开", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onOpenTask(task)
                }
            )
        }
        DropdownMenuItem(
            text = { Text("删除", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = {
                onDismiss()
                onDeleteTask(task)
            }
        )
        DropdownMenuItem(
            text = { Text("重新下载", fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
            onClick = {
                onDismiss()
                onRestartTask(task)
            }
        )
    }
}

@Composable
private fun TaskDetailsDialog(
    task: DownloadTaskUi,
    onDismiss: () -> Unit,
    onStartTask: (DownloadTaskUi) -> Unit,
    onPauseTask: (DownloadTaskUi) -> Unit,
    onCopyTaskUrl: (DownloadTaskUi) -> Unit,
    onOpenTask: (DownloadTaskUi) -> Unit
) {
    val canStart = task.canStart()
    val canPause = task.canPause()
    val canOpen = task.state == DownloadTaskState.Completed && task.outputUri != null
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 380.dp),
            shape = RectangleShape,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = task.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                SettingRow(label = "状态", value = task.status)
                SettingRow(label = "进度", value = "${(task.progress.coerceIn(0f, 1f) * 100).toInt()}%")
                if (task.totalSegments > 0) {
                    SettingRow(label = "分片", value = "${task.completedSegments}/${task.totalSegments}")
                    SettingRow(label = "大小", value = formatBytes(task.downloadedBytes))
                    SettingRow(label = "用时", value = formatDuration(task.elapsedMillis))
                }
                SettingRow(label = "平均速度", value = formatAverageSpeed(task.downloadedBytes, task.elapsedMillis))
                SettingRow(label = "开始时间", value = formatBeijingTime(task.startedAtMillis))
                SettingRow(label = "完成时间", value = formatBeijingTime(task.finishedAtMillis))
                SettingRow(label = "详情", value = task.detail)
                task.outputLabel?.let { output ->
                    SettingRow(label = "输出", value = output)
                }
                Text(
                    text = task.url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)
                ) {
                    if (canStart) {
                        TextButton(
                            onClick = {
                                onStartTask(task)
                                onDismiss()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                            Text(task.startActionLabel(), fontSize = 12.sp)
                        }
                    }
                    if (canPause) {
                        TextButton(
                            onClick = {
                                onPauseTask(task)
                                onDismiss()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("暂停", fontSize = 12.sp)
                        }
                    }
                    TextButton(
                        onClick = { onCopyTaskUrl(task) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("复制链接", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("关闭", fontSize = 12.sp)
                    }
                    if (canOpen) {
                        Button(
                            onClick = { onOpenTask(task) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("打开", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun DownloadTaskUi.canStart(): Boolean {
    return state == DownloadTaskState.Paused ||
        state == DownloadTaskState.Failed ||
        state == DownloadTaskState.Canceled
}

private fun DownloadTaskUi.canPause(): Boolean {
    return state == DownloadTaskState.Running ||
        state == DownloadTaskState.Queued
}

private fun DownloadTaskUi.startActionLabel(): String {
    return if (state == DownloadTaskState.Paused) "继续" else "开始"
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = RectangleShape
            )
            .background(MaterialTheme.colorScheme.surface, RectangleShape)
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return if (bytesPerSecond <= 0L) "测速中" else "${formatBytes(bytesPerSecond)}/s"
}

private fun formatAverageSpeed(bytes: Long, elapsedMillis: Long): String {
    if (bytes <= 0L || elapsedMillis <= 0L) return "-"
    return "${formatBytes(bytes * 1000 / elapsedMillis)}/s"
}

private fun formatBeijingTime(timestampMillis: Long?): String {
    if (timestampMillis == null || timestampMillis <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(timestampMillis))
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
