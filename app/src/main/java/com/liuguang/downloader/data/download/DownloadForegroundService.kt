package com.liuguang.downloader.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.liuguang.downloader.MainActivity
import com.liuguang.downloader.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

class DownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: M3u8DownloadEngine
    private val pendingRequests = ArrayDeque<QueuedDownloadRequest>()
    private val activeJobs = mutableMapOf<String, Job>()
    private val expectedStopTaskIds = mutableSetOf<String>()
    private var maxParallelTasks = DEFAULT_MAX_PARALLEL_TASKS
    private var foregroundStarted = false
    private var canceling = false

    override fun onCreate() {
        super.onCreate()
        DownloadTaskStore.initialize(applicationContext)
        engine = M3u8DownloadEngine(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> enqueueDownload(intent, startId)
            ACTION_CANCEL -> cancelDownloads()
            ACTION_PAUSE_TASK -> pauseTask(intent)
            ACTION_DELETE_TASK -> deleteTask(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        expectedStopTaskIds.addAll(activeJobs.keys)
        activeJobs.values.forEach { it.cancel() }
        activeJobs.keys.forEach(engine::cancelTaskRequests)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun enqueueDownload(intent: Intent, startId: Int) {
        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)?.trim().orEmpty()
        val directoryUri = intent.getStringExtra(EXTRA_DIRECTORY_URI)?.let(Uri::parse)
        maxParallelTasks = intent.getIntExtra(EXTRA_MAX_PARALLEL_TASKS, DEFAULT_MAX_PARALLEL_TASKS)
            .coerceAtLeast(1)
        val downloadThreadCount = intent.getIntExtra(EXTRA_DOWNLOAD_THREAD_COUNT, DEFAULT_DOWNLOAD_THREAD_COUNT)
            .coerceAtLeast(1)
        if (url.isBlank()) {
            stopSelf(startId)
            return
        }

        canceling = false
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val title = fileName.ifBlank { "流光下载-$taskId" }
        val request = QueuedDownloadRequest(
            taskId = taskId,
            title = title,
            url = url,
            directoryUri = directoryUri,
            downloadThreadCount = downloadThreadCount
        )

        DownloadTaskStore.enqueueTask(id = taskId, title = title, url = url)
        pendingRequests.addLast(request)
        ensureForeground()
        pumpQueue()
    }

    private fun pumpQueue() {
        while (activeJobs.size < maxParallelTasks && pendingRequests.isNotEmpty()) {
            startQueuedRequest(pendingRequests.removeFirst())
        }
        updateNotification()
        stopIfIdle()
    }

    private fun startQueuedRequest(request: QueuedDownloadRequest) {
        DownloadTaskStore.markTaskRunning(request.taskId)
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            engine.download(
                taskId = request.taskId,
                url = request.url,
                fileNameHint = request.title,
                customDirectoryUri = request.directoryUri,
                downloadThreadCount = request.downloadThreadCount
            ).catch { error ->
                if (error !is CancellationException && request.taskId !in expectedStopTaskIds) {
                    DownloadTaskStore.failTask(request.taskId, error.message ?: "下载失败")
                    updateNotification()
                }
            }.collect { progress ->
                DownloadTaskStore.applyProgress(request.taskId, progress)
                if (progress !is DownloadProgress.SegmentProgress && progress !is DownloadProgress.Muxing) {
                    updateNotification()
                }
            }

            activeJobs.remove(request.taskId)
            expectedStopTaskIds.remove(request.taskId)
            updateNotification()
            if (!canceling) {
                pumpQueue()
            }
        }
        activeJobs[request.taskId] = job
        job.start()
    }

    private fun cancelDownloads() {
        canceling = true
        pendingRequests.clear()
        expectedStopTaskIds.addAll(activeJobs.keys)
        activeJobs.keys.forEach(engine::cancelTaskRequests)
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        DownloadTaskStore.cancelActiveAndQueuedTasks()
        updateNotification()
        stopForeground(STOP_FOREGROUND_DETACH)
        foregroundStarted = false
        stopSelf()
    }

    private fun pauseTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val removedPending = pendingRequests.removeAll { it.taskId == taskId }
        val removedActive = activeJobs.remove(taskId)
        expectedStopTaskIds.add(taskId)
        engine.cancelTaskRequests(taskId)
        removedActive?.cancel()
        if (removedPending || removedActive != null || DownloadTaskStore.task(taskId) != null) {
            DownloadTaskStore.pauseTask(taskId)
        }
        if (removedActive == null) {
            expectedStopTaskIds.remove(taskId)
        }
        updateNotification()
        pumpQueue()
    }

    private fun deleteTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        pendingRequests.removeAll { it.taskId == taskId }
        val removedActive = activeJobs.remove(taskId)
        expectedStopTaskIds.add(taskId)
        engine.cancelTaskRequests(taskId)
        removedActive?.cancel()
        engine.clearTaskCache(taskId)
        DownloadTaskStore.removeTask(taskId)
        if (removedActive == null) {
            expectedStopTaskIds.remove(taskId)
        }
        updateNotification()
        pumpQueue()
    }

    private fun ensureForeground() {
        if (foregroundStarted) {
            updateNotification()
            return
        }
        startForeground(NOTIFICATION_ID, buildSummaryNotification())
        foregroundStarted = true
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildSummaryNotification())
    }

    private fun stopIfIdle() {
        if (activeJobs.isNotEmpty() || pendingRequests.isNotEmpty()) return
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun buildSummaryNotification(): Notification {
        val activeTasks = activeJobs.keys.mapNotNull(DownloadTaskStore::task)
        val queuedCount = pendingRequests.size
        val primaryTask = activeTasks.firstOrNull()
        val title = when {
            activeTasks.size == 1 -> primaryTask?.title.orEmpty()
            activeTasks.size > 1 -> "流光下载器"
            queuedCount > 0 -> "流光下载器"
            else -> "流光下载器"
        }
        val status = when {
            activeTasks.size > 1 -> "${activeTasks.size} 个下载中"
            primaryTask != null -> primaryTask.status
            queuedCount > 0 -> "$queuedCount 个等待中"
            else -> "已完成"
        }
        val detail = when {
            activeTasks.size > 1 -> "打开 App 查看实时速度和分片进度 · $queuedCount 个等待"
            primaryTask != null && queuedCount > 0 -> "打开 App 查看实时速度和分片进度 · $queuedCount 个等待"
            primaryTask != null -> "打开 App 查看实时速度和分片进度"
            queuedCount > 0 -> "任务已加入队列"
            else -> "没有运行中的任务"
        }
        return buildNotification(
            title = title.ifBlank { "流光下载器" },
            status = status,
            detail = detail,
            ongoing = activeTasks.isNotEmpty() || queuedCount > 0
        )
    }

    private fun buildNotification(
        title: String,
        status: String,
        detail: String,
        ongoing: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText("$status · $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openPendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) {
                    addAction(R.drawable.ic_launcher_monochrome, "取消", cancelPendingIntent)
                }
            }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "流光下载器的 m3u8 下载进度"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "com.liuguang.downloader.action.START_DOWNLOAD"
        private const val ACTION_CANCEL = "com.liuguang.downloader.action.CANCEL_DOWNLOAD"
        private const val ACTION_PAUSE_TASK = "com.liuguang.downloader.action.PAUSE_TASK"
        private const val ACTION_DELETE_TASK = "com.liuguang.downloader.action.DELETE_TASK"
        private const val EXTRA_TASK_ID = "extra_task_id"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_FILE_NAME = "extra_file_name"
        private const val EXTRA_DIRECTORY_URI = "extra_directory_uri"
        private const val EXTRA_MAX_PARALLEL_TASKS = "extra_max_parallel_tasks"
        private const val EXTRA_DOWNLOAD_THREAD_COUNT = "extra_download_thread_count"
        private const val CHANNEL_ID = "download_progress"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 2001
        private const val REQUEST_CANCEL = 2002
        private const val DEFAULT_MAX_PARALLEL_TASKS = 3
        private const val DEFAULT_DOWNLOAD_THREAD_COUNT = 8

        fun startDownload(
            context: Context,
            url: String,
            fileName: String,
            customDirectoryUri: Uri?,
            maxParallelTasks: Int,
            downloadThreadCount: Int,
            taskId: String? = null
        ) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
                taskId?.let { putExtra(EXTRA_TASK_ID, it) }
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MAX_PARALLEL_TASKS, maxParallelTasks)
                putExtra(EXTRA_DOWNLOAD_THREAD_COUNT, downloadThreadCount)
                customDirectoryUri?.let { putExtra(EXTRA_DIRECTORY_URI, it.toString()) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        fun pauseTask(context: Context, taskId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_PAUSE_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        fun deleteTask(context: Context, taskId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_DELETE_TASK
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }

        fun clearTaskCache(context: Context, taskId: String) {
            M3u8DownloadEngine(context.applicationContext).clearTaskCache(taskId)
        }
    }

    private data class QueuedDownloadRequest(
        val taskId: String,
        val title: String,
        val url: String,
        val directoryUri: Uri?,
        val downloadThreadCount: Int
    )
}
