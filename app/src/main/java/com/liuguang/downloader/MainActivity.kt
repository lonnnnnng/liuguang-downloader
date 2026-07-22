package com.liuguang.downloader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuguang.downloader.data.download.DownloadTaskState
import com.liuguang.downloader.data.update.ApkUpdateInstaller
import com.liuguang.downloader.data.update.UpdateStatus
import com.liuguang.downloader.data.update.UpdateUiState
import com.liuguang.downloader.ui.DownloadTaskUi
import com.liuguang.downloader.ui.DownloaderUiState
import com.liuguang.downloader.ui.DownloaderViewModel
import com.liuguang.downloader.ui.UpdateViewModel
import com.liuguang.downloader.ui.isSupportedDownloadUrl
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
    viewModel: DownloaderViewModel = viewModel(),
    updateViewModel: UpdateViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()
    var selectedScreen by remember { mutableStateOf(AppScreen.Download) }
    var openAddTaskDialogSignal by remember { mutableStateOf<Long?>(null) }
    var pendingDirectoryAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }
    var dismissedInstallationPromptId by remember { mutableStateOf<Long?>(null) }
    var pendingInstallFile by remember { mutableStateOf<java.io.File?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val permissionResult = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            if (permissionResult.isSuccess) {
                viewModel.setCustomDirectory(uri)
                pendingDirectoryAction?.invoke()
            }
            pendingDirectoryAction = null
        }
    }
    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val file = pendingInstallFile
        pendingInstallFile = null
        if (activity != null && file != null && ApkUpdateInstaller.canInstall(activity)) {
            ApkUpdateInstaller.launchInstaller(activity, file)
        }
    }

    fun installUpdate() {
        val file = updateState.downloadedFile ?: return
        val currentActivity = activity ?: return
        if (ApkUpdateInstaller.canInstall(currentActivity)) {
            ApkUpdateInstaller.launchInstaller(currentActivity, file)
        } else {
            // author: long - Android 8 起安装来源授权按应用管理，首次更新先让用户在系统页明确授权。
            pendingInstallFile = file
            unknownSourcesLauncher.launch(ApkUpdateInstaller.permissionIntent(currentActivity))
        }
    }

    BackHandler {
        showExitDialog = true
    }

    fun runAfterDirectoryAuthorization(action: () -> Unit) {
        if (state.customDirectoryUri != null && state.customDirectoryNeedsAuthorization) {
            pendingDirectoryAction = action
            directoryLauncher.launch(null)
        } else {
            action()
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
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    AppHeader(selectedScreen)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp)
                            .padding(top = 6.dp)
                    ) {
                        when (selectedScreen) {
                            AppScreen.Download -> DownloadScreen(
                                state = state,
                                openAddTaskDialogSignal = openAddTaskDialogSignal,
                                onAddTaskDialogSignalConsumed = { openAddTaskDialogSignal = null },
                                onUrlChange = viewModel::updateUrl,
                                onFileNameChange = viewModel::updateFileName,
                                onReadClipboard = viewModel::refreshClipboard,
                                onRefreshStorageInfo = viewModel::refreshStorageInfo,
                                onCreateTask = { runAfterDirectoryAuthorization(viewModel::startDownload) },
                                onStartTask = { task -> runAfterDirectoryAuthorization { viewModel.startTask(task) } },
                                onPauseTask = viewModel::pauseTask,
                                onCopyTaskUrl = viewModel::copyTaskUrl,
                                onOpenTask = viewModel::openTask,
                                onDeleteTask = viewModel::deleteTask,
                                onRestartTask = { task -> runAfterDirectoryAuthorization { viewModel.restartTask(task) } }
                            )
                            AppScreen.Settings -> SettingsScreen(
                                state = state,
                                updateState = updateState,
                                onChooseDirectory = { directoryLauncher.launch(null) },
                                onResetDirectory = viewModel::resetDirectory,
                                onMaxParallelChange = viewModel::setMaxParallelTasks,
                                onDownloadThreadChange = viewModel::setDownloadThreadCount,
                                onCheckUpdate = {
                                    dismissedUpdateVersion = null
                                    updateViewModel.checkForUpdates()
                                },
                                onDownloadUpdate = updateViewModel::downloadUpdate,
                                onInstallUpdate = ::installUpdate
                            )
                        }
                    }
                }

                AppBottomBar(
                    selectedScreen = selectedScreen,
                    onSelectScreen = { selectedScreen = it }
                )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("退出流光下载器？") },
            text = { Text("正在运行的下载任务会继续在后台执行。") },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = { activity?.finish() }) { Text("退出") }
            }
        )
    }

    val availableRelease = updateState.release
    if (
        updateState.status == UpdateStatus.Available &&
        availableRelease != null &&
        dismissedUpdateVersion != availableRelease.versionName
    ) {
        UpdateAvailableDialog(
            state = updateState,
            onDismiss = { dismissedUpdateVersion = availableRelease.versionName },
            onDownload = updateViewModel::downloadUpdate
        )
    }

    val readyFile = updateState.downloadedFile
    if (
        updateState.status == UpdateStatus.ReadyToInstall &&
        readyFile != null &&
        dismissedInstallationPromptId != updateState.installationPromptId
    ) {
        AlertDialog(
            onDismissRequest = { dismissedInstallationPromptId = updateState.installationPromptId },
            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
            title = { Text("更新已准备好") },
            text = { Text("安装包已完成摘要、应用标识、版本和签名校验。") },
            dismissButton = {
                TextButton(onClick = {
                    dismissedInstallationPromptId = updateState.installationPromptId
                    selectedScreen = AppScreen.Settings
                }) { Text("稍后") }
            },
            confirmButton = {
                Button(onClick = ::installUpdate) { Text("立即安装") }
            }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
        getStringExtra(EXTRA_DOWNLOAD_URL),
        getStringExtra(EXTRA_M3U8_URL),
        getStringExtra(Intent.EXTRA_TEXT),
        dataString
    )
    val url = candidates
        .map(String::trim)
        .firstOrNull(::isSupportedDownloadUrl)
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
private const val EXTRA_DOWNLOAD_URL = "com.liuguang.downloader.extra.DOWNLOAD_URL"
private const val EXTRA_FILE_NAME = "com.liuguang.downloader.extra.FILE_NAME"

@Composable
private fun AppHeader(selectedScreen: AppScreen) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = when (selectedScreen) {
                AppScreen.Download -> "下载"
                AppScreen.Settings -> "设置"
            },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            lineHeight = 20.sp,
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

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(52.dp)
                .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(role = Role.Button) {
                    onUrlChange("")
                    onFileNameChange("")
                    onRefreshStorageInfo()
                    showAddTaskDialog = true
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "新建下载任务",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
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
    val valid = isSupportedDownloadUrl(state.url)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 360.dp),
            shape = MaterialTheme.shapes.medium,
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
                    label = { Text("m3u8 / MP4 地址", fontSize = 11.sp) },
                    singleLine = false,
                    minLines = 5,
                    maxLines = 5,
                    shape = MaterialTheme.shapes.small,
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
                    shape = MaterialTheme.shapes.small,
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
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("取消", fontSize = 11.sp)
                    }
                    Button(
                        onClick = onCreateTask,
                        enabled = valid,
                        shape = MaterialTheme.shapes.small,
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
            .clip(MaterialTheme.shapes.small)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filters.forEach { filter ->
            val selected = selectedFilter == filter
            val count = tasks.count(filter::matches)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        } else {
                            Color.Transparent
                        }
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

@Composable
private fun SettingsScreen(
    state: DownloaderUiState,
    updateState: UpdateUiState,
    onChooseDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onMaxParallelChange: (Int) -> Unit,
    onDownloadThreadChange: (Int) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    var showDownloadCapabilities by remember { mutableStateOf(false) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SettingsCategory(title = "存储") {
                SettingsGroup {
                    SettingsItem(
                        icon = Icons.Default.Folder,
                        title = "保存目录",
                        summary = if (state.customDirectoryNeedsAuthorization) {
                            "需要重新授权 · ${state.customDirectoryLabel}"
                        } else {
                            state.customDirectoryLabel
                        },
                        summaryColor = if (state.customDirectoryNeedsAuthorization) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        onClick = onChooseDirectory,
                        trailing = { SettingsChevron() }
                    )
                    if (state.customDirectoryUri != null) {
                        SettingsDivider()
                        SettingsItem(
                            icon = Icons.Default.RestartAlt,
                            title = "恢复默认目录",
                            summary = "Downloads/liuguang-download",
                            onClick = onResetDirectory,
                            trailing = { SettingsChevron() }
                        )
                    }
                }
            }
        }

        item {
            SettingsCategory(title = "下载性能") {
                SettingsGroup {
                    SettingStepper(
                        icon = Icons.Default.Settings,
                        label = "最大并行任务",
                        summary = "同时运行的下载任务数量",
                        value = state.maxParallelTasks,
                        minValue = 1,
                        onValueChange = onMaxParallelChange
                    )
                    SettingsDivider()
                    SettingStepper(
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        label = "分片下载线程",
                        summary = "单个 HLS 任务的并发线程数",
                        value = state.downloadThreadCount,
                        minValue = 1,
                        onValueChange = onDownloadThreadChange
                    )
                }
            }
        }

        item {
            SettingsCategory(title = "应用") {
                SettingsGroup {
                    UpdateSettingsRow(
                        state = updateState,
                        onCheckUpdate = onCheckUpdate,
                        onDownloadUpdate = onDownloadUpdate,
                        onInstallUpdate = onInstallUpdate
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "下载能力",
                        summary = "输出格式、清晰度与兼容范围",
                        onClick = { showDownloadCapabilities = true },
                        trailing = { SettingsChevron() }
                    )
                }
            }
        }
    }

    if (showDownloadCapabilities) {
        DownloadCapabilitiesDialog(onDismiss = { showDownloadCapabilities = false })
    }
}

@Composable
private fun SettingsCategory(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        content()
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val itemModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
        .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
        .padding(horizontal = 10.dp, vertical = 8.dp)
    Row(
        modifier = itemModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = summary,
                color = summaryColor,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 54.dp),
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun UpdateSettingsRow(
    state: UpdateUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    Column {
        SettingsItem(
            icon = Icons.Default.SystemUpdate,
            title = "应用更新",
            summary = "当前 ${state.currentVersionName} · ${updateStatusText(state)}",
            summaryColor = if (state.status == UpdateStatus.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            trailing = {
                when (state.status) {
                    UpdateStatus.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    UpdateStatus.Downloading -> Text(
                        text = "${(state.downloadProgress * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    UpdateStatus.Available -> FilledTonalButton(
                        onClick = onDownloadUpdate,
                        modifier = Modifier.height(38.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("下载", fontSize = 11.sp) }
                    UpdateStatus.ReadyToInstall -> Button(
                        onClick = onInstallUpdate,
                        modifier = Modifier.height(38.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("安装", fontSize = 11.sp) }
                    else -> IconButton(
                        onClick = onCheckUpdate,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = if (state.status == UpdateStatus.Error) {
                                "重新检查更新"
                            } else {
                                "检查更新"
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        )
        if (state.status == UpdateStatus.Downloading) {
            LinearProgressIndicator(
                progress = { state.downloadProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 54.dp, end = 12.dp, bottom = 8.dp)
                    .height(3.dp)
            )
        }
    }
}

private fun updateStatusText(state: UpdateUiState): String = when (state.status) {
    UpdateStatus.Idle -> state.release?.let { "最新版本 ${it.versionName}" } ?: "点击检查更新"
    UpdateStatus.Checking -> "正在检查更新..."
    UpdateStatus.UpToDate -> state.message ?: "当前已是最新版本"
    UpdateStatus.Available -> "发现新版本 ${state.release?.versionName.orEmpty()}"
    UpdateStatus.Downloading -> "正在下载 ${(state.downloadProgress * 100).toInt()}%"
    UpdateStatus.ReadyToInstall -> state.message ?: "安装包已下载"
    UpdateStatus.Error -> state.message ?: "更新失败"
}

@Composable
private fun DownloadCapabilitiesDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 360.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "下载能力",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                CapabilityItem(label = "输出格式", value = "单个 MP4")
                CapabilityItem(label = "清晰度", value = "自动选择最高")
                CapabilityItem(label = "任务方式", value = "队列 + 前台服务")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CapabilityItem(label = "支持", value = "普通 / AES-128 TS-HLS")
                CapabilityItem(label = "暂不支持", value = "SAMPLE-AES、fMP4、BYTERANGE")
                CapabilityItem(label = "请求头", value = "暂不自定义")
            }
        }
    }
}

@Composable
private fun CapabilityItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            lineHeight = 13.sp
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        )
    }
}

@Composable
private fun UpdateAvailableDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    val release = state.release ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("发现新版本 ${release.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(release.title, fontWeight = FontWeight.SemiBold)
                if (release.notes.isNotBlank()) {
                    Text(
                        text = release.notes,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        confirmButton = { Button(onClick = onDownload) { Text("下载更新") } }
    )
}

@Composable
private fun AppBottomBar(
    selectedScreen: AppScreen,
    onSelectScreen: (AppScreen) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            AppBottomBarItem(
                selected = selectedScreen == AppScreen.Download,
                onClick = { onSelectScreen(AppScreen.Download) },
                icon = Icons.Default.Download,
                label = "下载",
                modifier = Modifier.weight(1f)
            )
            AppBottomBarItem(
                selected = selectedScreen == AppScreen.Settings,
                onClick = { onSelectScreen(AppScreen.Settings) },
                icon = Icons.Default.Settings,
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
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight(),
        color = Color.Transparent,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = label,
                color = contentColor,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SettingStepper(
    icon: ImageVector,
    label: String,
    summary: String,
    value: Int,
    minValue: Int,
    onValueChange: (Int) -> Unit
) {
    SettingsItem(
        icon = icon,
        title = label,
        summary = summary,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onValueChange(value - 1) },
                    enabled = value > minValue,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "减少$label",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = value.toString(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.widthIn(min = 28.dp, max = 56.dp)
                )
                IconButton(
                    onClick = { onValueChange(value + 1) },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "增加$label",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    )
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
    val statusColor = when (task.state) {
        DownloadTaskState.Failed -> MaterialTheme.colorScheme.error
        DownloadTaskState.Canceled,
        DownloadTaskState.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
        DownloadTaskState.Completed -> MaterialTheme.colorScheme.primary
        DownloadTaskState.Queued,
        DownloadTaskState.Running -> MaterialTheme.colorScheme.primary
    }
    val statusBackground = when (task.state) {
        DownloadTaskState.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
        DownloadTaskState.Completed,
        DownloadTaskState.Queued,
        DownloadTaskState.Running -> MaterialTheme.colorScheme.primaryContainer
        DownloadTaskState.Canceled,
        DownloadTaskState.Paused -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
    }

    Box {
        SurfaceCard(
            modifier = Modifier.combinedClickable(
                onClick = { showDetails = true },
                onLongClick = { menuExpanded = true }
            ),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                        color = statusColor,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(statusBackground)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = task.url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
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
            shape = MaterialTheme.shapes.medium,
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
                }
                if (task.downloadedBytes > 0L) {
                    SettingRow(label = "大小", value = formatBytes(task.downloadedBytes))
                }
                if (task.elapsedMillis > 0L) {
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
    val cardShape = MaterialTheme.shapes.medium
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = cardShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
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
