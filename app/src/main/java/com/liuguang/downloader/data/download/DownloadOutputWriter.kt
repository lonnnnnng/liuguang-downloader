package com.liuguang.downloader.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import java.io.File

class DownloadOutputWriter(
    private val context: Context
) {
    /**
     * long 2026-09-02 00:00:00: 下载前和发布时都解析唯一名称，避免同名任务覆盖用户已经保存的视频。
     */
    fun resolveUniqueDisplayName(displayName: String, customDirectoryUri: Uri?): String {
        val normalized = displayName.trim().ifBlank { "liuguang-download.mp4" }
        if (!outputNameExists(normalized, customDirectoryUri)) return normalized
        val baseName = normalized.removeSuffix(".mp4").removeSuffix(".MP4")
        var index = 1
        while (true) {
            val candidate = "$baseName ($index).mp4"
            if (!outputNameExists(candidate, customDirectoryUri)) return candidate
            index++
        }
    }

    fun availableBytes(customDirectoryUri: Uri?): Long {
        return runCatching {
            val cacheAvailable = StatFs(context.cacheDir.absolutePath).availableBytes
            val targetAvailable = if (customDirectoryUri == null) {
                StatFs(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                ).availableBytes
            } else {
                // 部分文档提供方不暴露真实文件路径，这时只能确保内部断点缓存仍有足够空间。
                resolveTreeStoragePath(customDirectoryUri)
                    ?.let { StatFs(it).availableBytes }
                    ?: cacheAvailable
            }
            minOf(cacheAvailable, targetAvailable)
        }.getOrDefault(0L)
    }

    private fun resolveTreeStoragePath(directoryUri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(directoryUri) }.getOrNull()
            ?: return null
        val volume = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (relativePath.isBlank()) root else "$root/$relativePath"
    }

    fun publishMp4(tempFile: File, displayName: String, customDirectoryUri: Uri?): PublishedOutput {
        return when {
            customDirectoryUri != null -> {
                val reservation = synchronized(outputReservationLock) {
                    val uniqueDisplayName = resolveUniqueDisplayName(displayName, customDirectoryUri)
                    reserveCustomDirectory(uniqueDisplayName, customDirectoryUri)
                }
                publishToCustomDirectory(tempFile, reservation)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                val reservation = synchronized(outputReservationLock) {
                    val uniqueDisplayName = resolveUniqueDisplayName(displayName, customDirectoryUri = null)
                    reserveDownloadsWithMediaStore(uniqueDisplayName)
                }
                publishToDownloadsWithMediaStore(tempFile, reservation)
            }
            else -> {
                val outputFile = synchronized(outputReservationLock) {
                    reserveLegacyDownloads(displayName)
                }
                publishToLegacyDownloads(tempFile, outputFile)
            }
        }
    }

    private fun outputNameExists(displayName: String, customDirectoryUri: Uri?): Boolean {
        return runCatching { if (customDirectoryUri != null) {
            DocumentFile.fromTreeUri(context, customDirectoryUri)
                ?.findFile(displayName)
                ?.isFile == true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/liuguang-download/"
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(relativePath, displayName),
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "liuguang-download/$displayName"
            ).isFile
        } }.getOrDefault(false)
    }

    private fun reserveCustomDirectory(displayName: String, directoryUri: Uri): CustomOutputReservation {
        val directory = DocumentFile.fromTreeUri(context, directoryUri)
            ?: error("自定义目录不可用")
        val targetFile = directory.createFile("video/mp4", displayName)
            ?: error("无法在自定义目录创建文件")
        return CustomOutputReservation(targetFile = targetFile, requestedDisplayName = displayName)
    }

    private fun publishToCustomDirectory(tempFile: File, reservation: CustomOutputReservation): PublishedOutput {
        return try {
            context.contentResolver.openOutputStream(reservation.targetFile.uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入自定义目录")
            PublishedOutput(
                label = reservation.targetFile.name ?: reservation.requestedDisplayName,
                uri = reservation.targetFile.uri.toString()
            )
        } catch (error: Throwable) {
            // long: 复制失败时只删除本次预留的空文件，避免自定义目录残留无法打开的半成品。
            reservation.targetFile.delete()
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun reserveDownloadsWithMediaStore(displayName: String): MediaStoreOutputReservation {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/liuguang-download")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建 Downloads 输出文件")
        return MediaStoreOutputReservation(uri = uri, displayName = displayName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloadsWithMediaStore(
        tempFile: File,
        reservation: MediaStoreOutputReservation
    ): PublishedOutput {
        val resolver = context.contentResolver
        try {
            resolver.openOutputStream(reservation.uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入 Downloads 输出文件")
            val publishedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(reservation.uri, publishedValues, null, null)
        } catch (error: Throwable) {
            resolver.delete(reservation.uri, null, null)
            throw error
        }
        return PublishedOutput(
            label = "Downloads/liuguang-download/${reservation.displayName}",
            uri = reservation.uri.toString()
        )
    }

    private fun reserveLegacyDownloads(displayName: String): File {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "liuguang-download"
        )
        return LegacyDownloadOutputReservation.reserve(directory, displayName)
    }

    private fun publishToLegacyDownloads(tempFile: File, outputFile: File): PublishedOutput {
        return try {
            tempFile.inputStream().use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            PublishedOutput(label = outputFile.absolutePath, uri = Uri.fromFile(outputFile).toString())
        } catch (error: Throwable) {
            // long: createNewFile 预留的目标只能由本次发布清理，绝不能回退到覆盖用户已有文件。
            outputFile.delete()
            throw error
        }
    }

    private data class CustomOutputReservation(
        val targetFile: DocumentFile,
        val requestedDisplayName: String
    )

    private data class MediaStoreOutputReservation(
        val uri: Uri,
        val displayName: String
    )

    private companion object {
        // long: ViewModel 与下载服务会各自创建 writer，进程级锁才能覆盖所有并行任务的目标预留阶段。
        private val outputReservationLock = Any()
    }
}

internal object LegacyDownloadOutputReservation {
    fun reserve(directory: File, displayName: String): File {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建 Downloads/liuguang-download 目录" }
        val normalized = displayName.trim().ifBlank { "liuguang-download.mp4" }
        val baseName = if (normalized.endsWith(".mp4", ignoreCase = true)) {
            normalized.dropLast(4)
        } else {
            normalized
        }
        var index = 0
        while (index < Int.MAX_VALUE) {
            val candidateName = if (index == 0) normalized else "$baseName ($index).mp4"
            val candidate = File(directory, candidateName)
            // long: createNewFile 把“判断不存在”和“占用文件名”合成一次原子操作，并发任务不会截断彼此的结果。
            if (candidate.createNewFile()) return candidate
            index++
        }
        error("无法生成唯一的下载文件名")
    }
}

data class PublishedOutput(
    val label: String,
    val uri: String
)
