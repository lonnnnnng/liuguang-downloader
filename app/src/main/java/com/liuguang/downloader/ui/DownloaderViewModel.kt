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
import com.liuguang.downloader.data.download.DownloadFailureCategory
import com.liuguang.downloader.data.download.DownloadFailureClassifier
import com.liuguang.downloader.data.download.DownloadPreflightResult
import com.liuguang.downloader.data.download.DownloadTaskSnapshot
import com.liuguang.downloader.data.download.DownloadTaskState
import com.liuguang.downloader.data.download.DownloadTaskStore
import com.liuguang.downloader.data.download.M3u8DownloadEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    val finishedAtMillis: Long? = null,
    val failureCategory: DownloadFailureCategory? = null,
    val retryAttempt: Int = 0,
    val lastFailureAtMillis: Long? = null
)

data class DownloadDraftItem(
    val url: String,
    val fileName: String = ""
)

private data class PreparedDownloadItem(
    val url: String,
    val displayName: String
)

private const val DEFAULT_DIRECTORY_LABEL = "liuguang-download"
private const val LEGACY_CUSTOM_DIRECTORY_LABEL = "自定义目录已选择"
private const val DEFAULT_MAX_PARALLEL_TASKS = 3
private const val DEFAULT_DOWNLOAD_THREAD_COUNT = 8

enum class DownloadPreflightStatus {
    Idle,
    Checking,
    Ready,
    Failed,
    Started
}

data class DownloaderUiState(
    val url: String = "",
    val fileName: String = "",
    val customDirectoryUri: String? = null,
    val customDirectoryLabel: String = DEFAULT_DIRECTORY_LABEL,
    val customDirectoryNeedsAuthorization: Boolean = false,
    val maxParallelTasks: Int = DEFAULT_MAX_PARALLEL_TASKS,
    val downloadThreadCount: Int = DEFAULT_DOWNLOAD_THREAD_COUNT,
    val storageUsedLabel: String = "",
    val storageTotalLabel: String = "",
    val storageAvailableLabel: String = "",
    val preflightStatus: DownloadPreflightStatus = DownloadPreflightStatus.Idle,
    val preflightMessage: String = "",
    val preflightExpectedBytes: Long = -1L,
    val preflightRequiredBytes: Long = -1L,
    val preflightAvailableBytes: Long = 0L,
    val preflightDisplayName: String = "",
    val preflightTaskCount: Int = 0,
    val preflightUnknownSizeCount: Int = 0,
    val downloadStartedEvent: Long? = null,
    val tasks: List<DownloadTaskUi> = emptyList()
)

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {
    private val downloadEngine by lazy { M3u8DownloadEngine(application.applicationContext) }
    private var preflightJob: Job? = null
    private var preflightGeneration = 0L
    private var suppliedDraftItems: List<DownloadDraftItem> = emptyList()
    private var preparedDownloadItems: List<PreparedDownloadItem> = emptyList()
    private val preferences = application.getSharedPreferences("downloader", Context.MODE_PRIVATE)
    private val savedCustomDirectoryUri = preferences.getString(KEY_CUSTOM_DIRECTORY_URI, null)
    private val savedCustomDirectoryNeedsAuthorization = savedCustomDirectoryUri
        ?.let { uriValue ->
            !runCatching {
                hasPersistedWritePermission(application.applicationContext, Uri.parse(uriValue))
            }.getOrDefault(false)
        }
        ?: false

    private val _uiState = MutableStateFlow(
        DownloaderUiState(
            url = readClipboardM3u8Candidate(application.applicationContext).orEmpty(),
            customDirectoryUri = savedCustomDirectoryUri,
            customDirectoryLabel = resolveDirectoryLabel(
                savedCustomDirectoryUri,
                preferences.getString(KEY_CUSTOM_DIRECTORY_LABEL, null)
            ),
            customDirectoryNeedsAuthorization = savedCustomDirectoryNeedsAuthorization,
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
        suppliedDraftItems = emptyList()
        _uiState.value = _uiState.value.copy(url = url).withoutPreflight()
    }

    fun updateFileName(fileName: String) {
        suppliedDraftItems = emptyList()
        _uiState.value = _uiState.value.copy(fileName = fileName).withoutPreflight()
    }

    fun setDownloadDraft(url: String, fileName: String = "") {
        setDownloadDraftItems(listOf(DownloadDraftItem(url = url, fileName = fileName)))
    }

    fun setDownloadDraftItems(items: List<DownloadDraftItem>) {
        val acceptedItems = items
            .filter { isSupportedDownloadUrl(it.url) }
            .take(MAX_BATCH_TASKS)
        suppliedDraftItems = acceptedItems
        _uiState.value = _uiState.value.copy(
            url = acceptedItems.joinToString("\n") { it.url },
            fileName = acceptedItems.singleOrNull()?.fileName.orEmpty()
        )
        _uiState.value = _uiState.value.withoutPreflight()
    }

    fun refreshClipboard() {
        val candidate = readClipboardM3u8Candidate(getApplication()) ?: return
        suppliedDraftItems = emptyList()
        _uiState.value = _uiState.value.copy(url = candidate).withoutPreflight()
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
            customDirectoryLabel = label,
            customDirectoryNeedsAuthorization = false
        ).withoutPreflight()
    }

    fun resetDirectory() {
        preferences.edit()
            .remove(KEY_CUSTOM_DIRECTORY_URI)
            .remove(KEY_CUSTOM_DIRECTORY_LABEL)
            .apply()
        _uiState.value = _uiState.value.copy(
            customDirectoryUri = null,
            customDirectoryLabel = DEFAULT_DIRECTORY_LABEL,
            customDirectoryNeedsAuthorization = false
        ).withoutPreflight()
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
        if (!isSupportedDownloadText(state.url)) return

        when (state.preflightStatus) {
            DownloadPreflightStatus.Ready -> launchPreflightedDownload(state)
            DownloadPreflightStatus.Checking -> Unit
            else -> beginPreflight(state)
        }
    }

    fun checkDownload() {
        val state = _uiState.value
        if (!isSupportedDownloadText(state.url) || state.preflightStatus == DownloadPreflightStatus.Checking) return
        if (state.preflightStatus == DownloadPreflightStatus.Ready) return
        beginPreflight(state)
    }

    fun resetPreflight() {
        preflightGeneration++
        preflightJob?.cancel()
        preparedDownloadItems = emptyList()
        _uiState.value = _uiState.value.withoutPreflight()
    }

    fun consumeDownloadStartedEvent() {
        _uiState.update { it.copy(downloadStartedEvent = null) }
    }

    private fun beginPreflight(state: DownloaderUiState) {
        val generation = ++preflightGeneration
        preflightJob?.cancel()
        val draftItems = buildDraftItems(state)
        if (draftItems.isEmpty()) return
        preparedDownloadItems = emptyList()
        _uiState.update {
            it.copy(
                preflightStatus = DownloadPreflightStatus.Checking,
                preflightMessage = "正在检查 ${draftItems.size} 个资源和存储空间",
                preflightExpectedBytes = -1L,
                preflightRequiredBytes = -1L,
                preflightAvailableBytes = 0L,
                preflightDisplayName = "",
                preflightTaskCount = draftItems.size,
                preflightUnknownSizeCount = 0,
                downloadStartedEvent = null
            )
        }
        preflightJob = viewModelScope.launch {
            runCatching {
                preflightItems(
                    items = draftItems,
                    customDirectoryUri = state.customDirectoryUri?.let(Uri::parse)
                )
            }.onSuccess { results ->
                if (generation != preflightGeneration) return@onSuccess
                val expectedBytes = results.sumOf { it.second.expectedBytes.coerceAtLeast(0L) }
                val requiredBytes = results.sumOf { it.second.requiredBytes.coerceAtLeast(0L) }
                val availableBytes = results.map { it.second.availableBytes }.filter { it > 0L }.minOrNull() ?: 0L
                val unknownSizeCount = results.count { it.second.expectedBytes <= 0L }
                val conflictCount = results.count { it.second.renamedBecauseConflict }
                val storageSufficient = results.all { it.second.storageSufficient } &&
                    (requiredBytes <= 0L || availableBytes <= 0L || availableBytes >= requiredBytes)
                preparedDownloadItems = results.map { (draft, result) ->
                    PreparedDownloadItem(url = draft.url, displayName = result.displayName)
                }
                if (!storageSufficient) {
                    _uiState.update {
                        it.copy(
                            preflightStatus = DownloadPreflightStatus.Failed,
                            preflightMessage = "存储空间不足：至少需要 ${formatStorageBytes(requiredBytes)}，" +
                                "当前剩余 ${formatStorageBytes(availableBytes)}",
                            preflightExpectedBytes = expectedBytes,
                            preflightRequiredBytes = requiredBytes,
                            preflightAvailableBytes = availableBytes,
                            preflightDisplayName = results.singleOrNull()?.second?.displayName.orEmpty(),
                            preflightUnknownSizeCount = unknownSizeCount
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            preflightStatus = DownloadPreflightStatus.Ready,
                            preflightMessage = when {
                                conflictCount > 0 -> "检查通过，$conflictCount 个同名文件将自动重命名"
                                results.size > 1 -> "${results.size} 个任务检查通过，可以开始下载"
                                else -> "检查通过，可以开始下载"
                            },
                            preflightExpectedBytes = expectedBytes,
                            preflightRequiredBytes = requiredBytes,
                            preflightAvailableBytes = availableBytes,
                            preflightDisplayName = results.singleOrNull()?.second?.displayName.orEmpty(),
                            preflightUnknownSizeCount = unknownSizeCount
                        )
                    }
                }
            }.onFailure { error ->
                if (generation != preflightGeneration) return@onFailure
                _uiState.update {
                    it.copy(
                        preflightStatus = DownloadPreflightStatus.Failed,
                        preflightMessage = error.message ?: "资源检查失败，请确认链接有效",
                        preflightExpectedBytes = -1L,
                        preflightRequiredBytes = -1L,
                        preflightAvailableBytes = 0L
                    )
                }
            }
        }
    }

    private fun launchPreflightedDownload(state: DownloaderUiState) {
        if (preparedDownloadItems.isEmpty()) return
        preparedDownloadItems.forEach { item ->
            DownloadForegroundService.startDownload(
                context = getApplication(),
                url = item.url,
                fileName = item.displayName,
                customDirectoryUri = state.customDirectoryUri?.let(Uri::parse),
                maxParallelTasks = state.maxParallelTasks,
                downloadThreadCount = state.downloadThreadCount
            )
        }
        val startedCount = preparedDownloadItems.size
        preparedDownloadItems = emptyList()
        suppliedDraftItems = emptyList()
        _uiState.value = _uiState.value.copy(
            fileName = "",
            preflightStatus = DownloadPreflightStatus.Started,
            preflightMessage = "$startedCount 个任务已加入下载队列",
            downloadStartedEvent = System.nanoTime()
        )
    }

    private suspend fun preflightItems(
        items: List<DownloadDraftItem>,
        customDirectoryUri: Uri?
    ): List<Pair<DownloadDraftItem, DownloadPreflightResult>> = coroutineScope {
        val semaphore = Semaphore(PREFLIGHT_PARALLELISM)
        items.mapIndexed { index, item ->
            async {
                semaphore.withPermit {
                    val result = runCatching {
                        downloadEngine.preflight(
                            url = item.url,
                            fileNameHint = item.fileName,
                            customDirectoryUri = customDirectoryUri
                        )
                    }.getOrElse { error ->
                        val failure = DownloadFailureClassifier.classify(error)
                        throw IllegalStateException("第 ${index + 1} 个资源检查失败：${failure.message}", error)
                    }
                    item to result
                }
            }
        }.awaitAll()
    }

    private fun buildDraftItems(state: DownloaderUiState): List<DownloadDraftItem> {
        val urls = parseDownloadUrls(state.url)
        if (urls.isEmpty()) return emptyList()
        val suppliedByUrl = suppliedDraftItems.associateBy { it.url }
        return urls.mapIndexed { index, url ->
            val suppliedName = suppliedByUrl[url]?.fileName.orEmpty()
            val fileName = when {
                urls.size == 1 && state.fileName.isNotBlank() -> state.fileName
                urls.size == 1 && suppliedName.isNotBlank() -> suppliedName
                urls.size > 1 && state.fileName.isNotBlank() -> {
                    "${state.fileName.trim()}-${(index + 1).toString().padStart(2, '0')}"
                }
                suppliedName.isNotBlank() -> suppliedName
                else -> deriveFileName(url, index)
            }
            DownloadDraftItem(url = url, fileName = fileName)
        }
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
        clipboard.setPrimaryClip(ClipData.newPlainText("download-url", task.url))
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
        if (!isSupportedDownloadUrl(task.url)) return
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
        private const val MAX_BATCH_TASKS = 20
        private const val PREFLIGHT_PARALLELISM = 3
    }

    private fun DownloaderUiState.withoutPreflight(): DownloaderUiState {
        return copy(
            preflightStatus = DownloadPreflightStatus.Idle,
            preflightMessage = "",
            preflightExpectedBytes = -1L,
            preflightRequiredBytes = -1L,
            preflightAvailableBytes = 0L,
            preflightDisplayName = "",
            preflightTaskCount = 0,
            preflightUnknownSizeCount = 0,
            downloadStartedEvent = null
        )
    }
}

private fun resolveDirectoryLabel(uriValue: String?, savedLabel: String?): String {
    if (uriValue.isNullOrBlank()) return DEFAULT_DIRECTORY_LABEL
    if (!savedLabel.isNullOrBlank() && savedLabel != LEGACY_CUSTOM_DIRECTORY_LABEL) return savedLabel
    return runCatching { formatDirectoryLabel(Uri.parse(uriValue)) }
        .getOrDefault(Uri.decode(uriValue))
}

private fun hasPersistedWritePermission(context: Context, uri: Uri): Boolean {
    return context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
    }
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
        finishedAtMillis = finishedAtMillis,
        failureCategory = failureCategory,
        retryAttempt = retryAttempt,
        lastFailureAtMillis = lastFailureAtMillis
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
    return text.takeIf(::isSupportedDownloadText)
}

fun parseDownloadUrls(value: String): List<String> {
    return value.lineSequence()
        .map { it.trim().removePrefix("- ").trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}

fun isSupportedDownloadText(value: String): Boolean {
    if (value.length > 65_536) return false
    val urls = parseDownloadUrls(value)
    return urls.size in 1..20 && urls.all(::isSupportedDownloadUrl)
}

private fun deriveFileName(url: String, index: Int): String {
    val segments = runCatching { Uri.parse(url).pathSegments }.getOrDefault(emptyList())
    val last = segments.lastOrNull().orEmpty()
    val candidate = when {
        last.endsWith(".mp4", ignoreCase = true) -> last.substringBeforeLast('.')
        last.endsWith(".m3u8", ignoreCase = true) -> segments.dropLast(1).lastOrNull().orEmpty()
        else -> last.substringBeforeLast('.', missingDelimiterValue = last)
    }
    return candidate.ifBlank { "流光下载-${(index + 1).toString().padStart(2, '0')}" }
}

fun isM3u8Url(value: String): Boolean {
    val normalized = value.trim()
    return normalized.length <= 8_192 &&
        (normalized.startsWith("http://") || normalized.startsWith("https://")) &&
        normalized.contains(".m3u8", ignoreCase = true)
}

fun isMp4Url(value: String): Boolean {
    val normalized = value.trim()
    return normalized.length <= 8_192 &&
        (normalized.startsWith("http://") || normalized.startsWith("https://")) &&
        normalized.substringBefore("?").contains(".mp4", ignoreCase = true)
}

fun isSupportedDownloadUrl(value: String): Boolean {
    return isM3u8Url(value) || isMp4Url(value)
}
