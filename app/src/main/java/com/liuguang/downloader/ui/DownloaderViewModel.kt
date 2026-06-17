package com.liuguang.downloader.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuguang.downloader.data.download.DownloadForegroundService
import com.liuguang.downloader.data.download.DownloadTaskSnapshot
import com.liuguang.downloader.data.download.DownloadTaskState
import com.liuguang.downloader.data.download.DownloadTaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadTaskUi(
    val id: String,
    val title: String,
    val url: String,
    val state: DownloadTaskState,
    val status: String,
    val progress: Float,
    val detail: String,
    val outputLabel: String? = null,
    val outputUri: String? = null,
    val isRunning: Boolean = false,
    val isFailed: Boolean = false,
    val completedSegments: Int = 0,
    val totalSegments: Int = 0,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val elapsedMillis: Long = 0L,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null
)

private const val DEFAULT_DIRECTORY_LABEL = "liuguang-download"
private const val LEGACY_CUSTOM_DIRECTORY_LABEL = "自定义目录已选择"
private const val DEFAULT_MAX_PARALLEL_TASKS = 3
private const val DEFAULT_DOWNLOAD_THREAD_COUNT = 8

data class DownloaderUiState(
    val url: String = "",
    val fileName: String = "",
    val customDirectoryUri: String? = null,
    val customDirectoryLabel: String = DEFAULT_DIRECTORY_LABEL,
    val maxParallelTasks: Int = DEFAULT_MAX_PARALLEL_TASKS,
    val downloadThreadCount: Int = DEFAULT_DOWNLOAD_THREAD_COUNT,
    val storageUsedLabel: String = "",
    val storageTotalLabel: String = "",
    val storageAvailableLabel: String = "",
    val tasks: List<DownloadTaskUi> = emptyList()
)

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("downloader", Context.MODE_PRIVATE)
    private val savedCustomDirectoryUri = preferences.getString(KEY_CUSTOM_DIRECTORY_URI, null)

    private val _uiState = MutableStateFlow(
        DownloaderUiState(
            url = readClipboardM3u8Candidate(application.applicationContext).orEmpty(),
            customDirectoryUri = savedCustomDirectoryUri,
            customDirectoryLabel = resolveDirectoryLabel(
                savedCustomDirectoryUri,
                preferences.getString(KEY_CUSTOM_DIRECTORY_LABEL, null)
            ),
            maxParallelTasks = preferences.getInt(KEY_MAX_PARALLEL_TASKS, DEFAULT_MAX_PARALLEL_TASKS)
                .coerceAtLeast(1),
            downloadThreadCount = preferences.getInt(KEY_DOWNLOAD_THREAD_COUNT, DEFAULT_DOWNLOAD_THREAD_COUNT)
                .coerceAtLeast(1)
        )
    )
    val uiState: StateFlow<DownloaderUiState> = _uiState.asStateFlow()

    init {
        DownloadTaskStore.initialize(application.applicationContext)
        refreshStorageInfo()
        viewModelScope.launch {
            DownloadTaskStore.tasks.collect { tasks ->
                _uiState.update { state ->
                    state.copy(tasks = tasks.map(DownloadTaskSnapshot::toUi))
                }
            }
        }
    }

    fun updateUrl(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    fun updateFileName(fileName: String) {
        _uiState.value = _uiState.value.copy(fileName = fileName)
    }

    fun setDownloadDraft(url: String, fileName: String = "") {
        _uiState.value = _uiState.value.copy(
            url = url,
            fileName = fileName
        )
    }

    fun refreshClipboard() {
        val candidate = readClipboardM3u8Candidate(getApplication()) ?: return
        _uiState.value = _uiState.value.copy(url = candidate)
    }

    fun refreshStorageInfo() {
        val storageInfo = readStorageInfo()
        _uiState.value = _uiState.value.copy(
            storageUsedLabel = storageInfo.usedLabel,
            storageTotalLabel = storageInfo.totalLabel,
            storageAvailableLabel = storageInfo.availableLabel
        )
    }

    fun setCustomDirectory(uri: Uri) {
        val label = formatDirectoryLabel(uri)
        preferences.edit()
            .putString(KEY_CUSTOM_DIRECTORY_URI, uri.toString())
            .putString(KEY_CUSTOM_DIRECTORY_LABEL, label)
            .apply()
        _uiState.value = _uiState.value.copy(
            customDirectoryUri = uri.toString(),
            customDirectoryLabel = label
        )
    }

    fun resetDirectory() {
        preferences.edit()
            .remove(KEY_CUSTOM_DIRECTORY_URI)
            .remove(KEY_CUSTOM_DIRECTORY_LABEL)
            .apply()
        _uiState.value = _uiState.value.copy(
            customDirectoryUri = null,
            customDirectoryLabel = DEFAULT_DIRECTORY_LABEL
        )
    }

    fun setMaxParallelTasks(value: Int) {
        val normalized = value.coerceAtLeast(1)
        preferences.edit().putInt(KEY_MAX_PARALLEL_TASKS, normalized).apply()
        _uiState.value = _uiState.value.copy(maxParallelTasks = normalized)
    }

    fun setDownloadThreadCount(value: Int) {
        val normalized = value.coerceAtLeast(1)
        preferences.edit().putInt(KEY_DOWNLOAD_THREAD_COUNT, normalized).apply()
        _uiState.value = _uiState.value.copy(downloadThreadCount = normalized)
    }

    fun startDownload() {
        val state = _uiState.value
        val url = state.url.trim()
        if (!isM3u8Url(url)) return

        val taskId = System.currentTimeMillis().toString()
        val fileName = state.fileName.ifBlank { "流光下载-$taskId" }
        DownloadForegroundService.startDownload(
            context = getApplication(),
            url = url,
            fileName = fileName,
            customDirectoryUri = state.customDirectoryUri?.let(Uri::parse),
            maxParallelTasks = state.maxParallelTasks,
            downloadThreadCount = state.downloadThreadCount
        )
        _uiState.value = _uiState.value.copy(fileName = "")
    }

    fun cancelRunningDownload() {
        DownloadForegroundService.cancel(getApplication())
    }

    fun startTask(task: DownloadTaskUi) {
        if (task.state == DownloadTaskState.Running || task.state == DownloadTaskState.Queued) return
        startDownloadForTask(task = task, reuseTaskId = true)
    }

    fun pauseTask(task: DownloadTaskUi) {
        if (task.state != DownloadTaskState.Running && task.state != DownloadTaskState.Queued) return
        DownloadForegroundService.pauseTask(getApplication(), task.id)
    }

    fun restartTask(task: DownloadTaskUi) {
        DownloadForegroundService.clearTaskCache(getApplication(), task.id)
        startDownloadForTask(task = task, reuseTaskId = false)
    }

    fun deleteTask(task: DownloadTaskUi) {
        if (task.state == DownloadTaskState.Running || task.state == DownloadTaskState.Queued) {
            DownloadForegroundService.deleteTask(getApplication(), task.id)
        } else {
            DownloadTaskStore.removeTask(task.id)
        }
    }

    fun copyTaskUrl(task: DownloadTaskUi) {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("m3u8", task.url))
    }

    fun openTask(task: DownloadTaskUi) {
        val uri = task.outputUri?.let(Uri::parse) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
    }

    private fun startDownloadForTask(task: DownloadTaskUi, reuseTaskId: Boolean) {
        if (!isM3u8Url(task.url)) return
        val state = _uiState.value
        DownloadForegroundService.startDownload(
            context = getApplication(),
            url = task.url,
            fileName = task.title,
            customDirectoryUri = state.customDirectoryUri?.let(Uri::parse),
            maxParallelTasks = state.maxParallelTasks,
            downloadThreadCount = state.downloadThreadCount,
            taskId = task.id.takeIf { reuseTaskId }
        )
    }

    private companion object {
        private const val KEY_CUSTOM_DIRECTORY_URI = "custom_directory_uri"
        private const val KEY_CUSTOM_DIRECTORY_LABEL = "custom_directory_label"
        private const val KEY_MAX_PARALLEL_TASKS = "max_parallel_tasks"
        private const val KEY_DOWNLOAD_THREAD_COUNT = "download_thread_count"
    }
}

private fun resolveDirectoryLabel(uriValue: String?, savedLabel: String?): String {
    if (uriValue.isNullOrBlank()) return DEFAULT_DIRECTORY_LABEL
    if (!savedLabel.isNullOrBlank() && savedLabel != LEGACY_CUSTOM_DIRECTORY_LABEL) return savedLabel
    return runCatching { formatDirectoryLabel(Uri.parse(uriValue)) }
        .getOrDefault(Uri.decode(uriValue))
}

private fun formatDirectoryLabel(uri: Uri): String {
    val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?.let(Uri::decode)
        ?.takeIf { it.isNotBlank() }
    if (treeDocumentId != null) {
        return when {
            treeDocumentId.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            treeDocumentId.startsWith("primary:", ignoreCase = true) -> {
                val relativePath = treeDocumentId.substringAfter(":").trim('/')
                if (relativePath.isBlank()) "/storage/emulated/0" else "/storage/emulated/0/$relativePath"
            }
            ":" in treeDocumentId -> {
                val volume = treeDocumentId.substringBefore(":")
                val relativePath = treeDocumentId.substringAfter(":").trim('/')
                if (relativePath.isBlank()) volume else "$volume:/$relativePath"
            }
            else -> treeDocumentId
        }
    }
    return Uri.decode(uri.toString())
}

private data class StorageInfo(
    val usedLabel: String,
    val totalLabel: String,
    val availableLabel: String
)

private fun readStorageInfo(): StorageInfo {
    return runCatching {
        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val totalBytes = statFs.totalBytes
        val availableBytes = statFs.availableBytes
        val usedBytes = (totalBytes - availableBytes).coerceAtLeast(0L)
        StorageInfo(
            usedLabel = formatStorageBytes(usedBytes),
            totalLabel = formatStorageBytes(totalBytes),
            availableLabel = formatStorageBytes(availableBytes)
        )
    }.getOrDefault(
        StorageInfo(
            usedLabel = "-",
            totalLabel = "-",
            availableLabel = "-"
        )
    )
}

private fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

private fun DownloadTaskSnapshot.toUi(): DownloadTaskUi {
    return DownloadTaskUi(
        id = id,
        title = title,
        url = url,
        state = state,
        status = status,
        progress = progress,
        detail = detail,
        outputLabel = outputLabel,
        outputUri = outputUri,
        isRunning = isRunning,
        isFailed = isFailed,
        completedSegments = completedSegments,
        totalSegments = totalSegments,
        downloadedBytes = downloadedBytes,
        speedBytesPerSecond = speedBytesPerSecond,
        elapsedMillis = elapsedMillis,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis
    )
}

fun readClipboardM3u8Candidate(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.trim()
        .orEmpty()
    return text.takeIf(::isM3u8Url)
}

fun isM3u8Url(value: String): Boolean {
    val normalized = value.trim()
    return (normalized.startsWith("http://") || normalized.startsWith("https://")) &&
        normalized.contains(".m3u8", ignoreCase = true)
}
