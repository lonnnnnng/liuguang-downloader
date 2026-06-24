package com.liuguang.downloader.data.download

import java.io.File

data class HlsDownloadArtifact(
    val taskId: String,
    val workingDirectory: File,
    val segmentFiles: List<File>,
    val tempMp4File: File
)

sealed interface DownloadProgress {
    data class Preparing(val message: String) : DownloadProgress
    data class VariantSelected(val label: String, val url: String) : DownloadProgress
    data class SegmentProgress(
        val completedSegments: Int,
        val totalSegments: Int,
        val downloadedBytes: Long,
        val speedBytesPerSecond: Long,
        val elapsedMillis: Long
    ) : DownloadProgress
    data class FileProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSecond: Long,
        val elapsedMillis: Long
    ) : DownloadProgress
    data class Muxing(
        val completedSegments: Int,
        val totalSegments: Int,
        val downloadedBytes: Long,
        val elapsedMillis: Long
    ) : DownloadProgress
    data class Publishing(val message: String) : DownloadProgress
    data class Completed(
        val outputLabel: String,
        val outputUri: String,
        val downloadedBytes: Long,
        val elapsedMillis: Long,
        val completedSegments: Int,
        val totalSegments: Int
    ) : DownloadProgress
}
