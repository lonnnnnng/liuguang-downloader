package com.liuguang.downloader.data.download

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

object DownloadTaskStore {
    private val _tasks = MutableStateFlow<List<DownloadTaskSnapshot>>(emptyList())
    val tasks: StateFlow<List<DownloadTaskSnapshot>> = _tasks.asStateFlow()
    private var preferences: SharedPreferences? = null
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val restoredTasks = preferences
            ?.getString(KEY_TASKS_JSON, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeTasks)
            .orEmpty()
        if (_tasks.value.isEmpty()) {
            _tasks.value = restoredTasks
        } else {
            persistTasks(_tasks.value)
        }
        initialized = true
    }

    fun enqueueTask(id: String, title: String, url: String) {
        val task = DownloadTaskSnapshot(
            id = id,
            title = title,
            url = url,
            state = DownloadTaskState.Queued,
            status = "等待中",
            progress = 0f,
            detail = "已加入下载队列"
        )
        updateTasks { current -> listOf(task) + current.filterNot { it.id == id } }
    }

    fun markTaskRunning(id: String) {
        val now = System.currentTimeMillis()
        updateTask(id) { current ->
            current.copy(
                state = DownloadTaskState.Running,
                status = "准备中",
                detail = "正在创建下载任务",
                isRunning = true,
                isFailed = false,
                startedAtMillis = current.startedAtMillis ?: now
            )
        }
    }

    fun task(id: String): DownloadTaskSnapshot? {
        return _tasks.value.firstOrNull { it.id == id }
    }

    fun applyProgress(id: String, progress: DownloadProgress) {
        updateTask(id) { current -> current.applyProgress(progress) }
    }

    fun failTask(id: String, message: String) {
        updateTask(id) { current ->
            current.copy(
                state = DownloadTaskState.Failed,
                status = "失败",
                detail = message,
                isRunning = false,
                isFailed = true,
                finishedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun pauseTask(id: String) {
        updateTask(id) { current ->
            current.copy(
                state = DownloadTaskState.Paused,
                status = "已暂停",
                detail = "任务已暂停，点击继续会继续下载",
                isRunning = false,
                isFailed = false,
                finishedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun removeTask(id: String) {
        updateTasks { current -> current.filterNot { it.id == id } }
    }

    fun cancelActiveAndQueuedTasks() {
        updateTasks { current ->
            current.map { task ->
                if (task.isRunning || task.state == DownloadTaskState.Queued) {
                    task.copy(
                        state = DownloadTaskState.Canceled,
                        status = "已取消",
                        detail = "任务已取消",
                        isRunning = false,
                        finishedAtMillis = System.currentTimeMillis()
                    )
                } else {
                    task
                }
            }
        }
    }

    private fun updateTask(id: String, transform: (DownloadTaskSnapshot) -> DownloadTaskSnapshot) {
        updateTasks { current ->
            current.map { task ->
                if (task.id == id) transform(task) else task
            }
        }
    }

    private fun updateTasks(transform: (List<DownloadTaskSnapshot>) -> List<DownloadTaskSnapshot>) {
        _tasks.update(transform)
        persistTasks(_tasks.value)
    }

    private fun DownloadTaskSnapshot.applyProgress(progress: DownloadProgress): DownloadTaskSnapshot {
        return when (progress) {
            is DownloadProgress.Preparing -> copy(
                state = DownloadTaskState.Running,
                status = "准备中",
                detail = progress.message,
                isRunning = true,
                isFailed = false,
                outputLabel = null,
                outputUri = null
            )
            is DownloadProgress.VariantSelected -> copy(
                state = DownloadTaskState.Running,
                status = "已选择清晰度",
                detail = "${progress.label} · ${progress.url}",
                isRunning = true
            )
            is DownloadProgress.SegmentProgress -> copy(
                state = DownloadTaskState.Running,
                status = "下载中",
                progress = progress.completedSegments.toFloat() / progress.totalSegments.coerceAtLeast(1),
                detail = "${progress.completedSegments}/${progress.totalSegments} 分片 · " +
                    "${formatBytes(progress.downloadedBytes)} · " +
                    "${formatSpeed(progress.speedBytesPerSecond)} · " +
                    formatDuration(progress.elapsedMillis),
                isRunning = true,
                completedSegments = progress.completedSegments,
                totalSegments = progress.totalSegments,
                downloadedBytes = progress.downloadedBytes,
                speedBytesPerSecond = progress.speedBytesPerSecond,
                elapsedMillis = progress.elapsedMillis
            )
            is DownloadProgress.Muxing -> copy(
                state = DownloadTaskState.Running,
                status = "合并 MP4",
                progress = progress.completedSegments.toFloat() / progress.totalSegments.coerceAtLeast(1),
                detail = "正在合并 ${progress.completedSegments}/${progress.totalSegments} 个分片 · " +
                    "${formatBytes(progress.downloadedBytes)} · " +
                    formatDuration(progress.elapsedMillis),
                isRunning = true,
                completedSegments = progress.completedSegments,
                totalSegments = progress.totalSegments,
                downloadedBytes = progress.downloadedBytes,
                speedBytesPerSecond = 0L,
                elapsedMillis = progress.elapsedMillis
            )
            is DownloadProgress.Publishing -> copy(
                state = DownloadTaskState.Running,
                status = "保存文件",
                detail = progress.message,
                isRunning = true
            )
            is DownloadProgress.Completed -> copy(
                state = DownloadTaskState.Completed,
                status = "已完成",
                progress = 1f,
                detail = "已保存：${progress.outputLabel}",
                outputLabel = progress.outputLabel,
                outputUri = progress.outputUri,
                isRunning = false,
                isFailed = false,
                completedSegments = progress.completedSegments,
                totalSegments = progress.totalSegments,
                downloadedBytes = progress.downloadedBytes,
                speedBytesPerSecond = 0L,
                elapsedMillis = progress.elapsedMillis,
                finishedAtMillis = System.currentTimeMillis()
            )
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

    private fun persistTasks(tasks: List<DownloadTaskSnapshot>) {
        val prefs = preferences ?: return
        prefs.edit()
            .putString(KEY_TASKS_JSON, encodeTasks(tasks).toString())
            .apply()
    }

    private fun encodeTasks(tasks: List<DownloadTaskSnapshot>): JSONArray {
        return JSONArray().apply {
            tasks.forEach { task ->
                put(
                    JSONObject()
                        .put("id", task.id)
                        .put("title", task.title)
                        .put("url", task.url)
                        .put("state", task.state.name)
                        .put("status", task.status)
                        .put("progress", task.progress.toDouble())
                        .put("detail", task.detail)
                        .putNullable("outputLabel", task.outputLabel)
                        .putNullable("outputUri", task.outputUri)
                        .put("isRunning", task.isRunning)
                        .put("isFailed", task.isFailed)
                        .put("completedSegments", task.completedSegments)
                        .put("totalSegments", task.totalSegments)
                        .put("downloadedBytes", task.downloadedBytes)
                        .put("speedBytesPerSecond", task.speedBytesPerSecond)
                        .put("elapsedMillis", task.elapsedMillis)
                        .put("createdAtMillis", task.createdAtMillis)
                        .putNullable("startedAtMillis", task.startedAtMillis)
                        .putNullable("finishedAtMillis", task.finishedAtMillis)
                )
            }
        }
    }

    private fun decodeTasks(json: String): List<DownloadTaskSnapshot> {
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val snapshot = item.toTaskSnapshot() ?: continue
                    add(snapshot.restoredForColdStart())
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun JSONObject.toTaskSnapshot(): DownloadTaskSnapshot? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: "流光下载-$id"
        val url = optString("url").takeIf { it.isNotBlank() } ?: return null
        val state = runCatching {
            DownloadTaskState.valueOf(optString("state"))
        }.getOrDefault(DownloadTaskState.Paused)
        return DownloadTaskSnapshot(
            id = id,
            title = title,
            url = url,
            state = state,
            status = optString("status").ifBlank { state.defaultStatus() },
            progress = optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
            detail = optString("detail").ifBlank { "历史任务" },
            outputLabel = optNullableString("outputLabel"),
            outputUri = optNullableString("outputUri"),
            isRunning = optBoolean("isRunning", false),
            isFailed = optBoolean("isFailed", state == DownloadTaskState.Failed),
            completedSegments = optInt("completedSegments", 0),
            totalSegments = optInt("totalSegments", 0),
            downloadedBytes = optLong("downloadedBytes", 0L),
            speedBytesPerSecond = optLong("speedBytesPerSecond", 0L),
            elapsedMillis = optLong("elapsedMillis", 0L),
            createdAtMillis = optLong("createdAtMillis", System.currentTimeMillis()),
            startedAtMillis = optNullableLong("startedAtMillis"),
            finishedAtMillis = optNullableLong("finishedAtMillis")
        )
    }

    private fun DownloadTaskSnapshot.restoredForColdStart(): DownloadTaskSnapshot {
        return if (state == DownloadTaskState.Running || state == DownloadTaskState.Queued || isRunning) {
            copy(
                state = DownloadTaskState.Paused,
                status = "已暂停",
                detail = "任务中断，点击继续会继续下载",
                isRunning = false,
                isFailed = false,
                speedBytesPerSecond = 0L
            )
        } else {
            copy(
                isRunning = false,
                speedBytesPerSecond = 0L,
                detail = if (state == DownloadTaskState.Paused && detail.contains("开始后")) {
                    "任务已暂停，点击继续会继续下载"
                } else {
                    detail
                }
            )
        }
    }

    private fun DownloadTaskState.defaultStatus(): String {
        return when (this) {
            DownloadTaskState.Queued -> "等待中"
            DownloadTaskState.Running -> "下载中"
            DownloadTaskState.Paused -> "已暂停"
            DownloadTaskState.Completed -> "已完成"
            DownloadTaskState.Failed -> "失败"
            DownloadTaskState.Canceled -> "已取消"
        }
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putNullable(key: String, value: Long?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        return if (has(key) && !isNull(key)) optString(key) else null
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        return if (has(key) && !isNull(key)) optLong(key) else null
    }

    private const val PREFERENCES_NAME = "download_task_store"
    private const val KEY_TASKS_JSON = "tasks_json"
}
