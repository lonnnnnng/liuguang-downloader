package com.liuguang.downloader.data.download

enum class DownloadTaskState {
    Queued,
    Running,
    Paused,
    Completed,
    Failed,
    Canceled
}

data class DownloadTaskSnapshot(
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
    val createdAtMillis: Long = System.currentTimeMillis(),
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null
)
