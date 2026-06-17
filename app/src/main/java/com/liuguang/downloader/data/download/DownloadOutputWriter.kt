package com.liuguang.downloader.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

class DownloadOutputWriter(
    private val context: Context
) {
    fun publishMp4(tempFile: File, displayName: String, customDirectoryUri: Uri?): PublishedOutput {
        return if (customDirectoryUri != null) {
            publishToCustomDirectory(tempFile, displayName, customDirectoryUri)
        } else {
            publishToDownloads(tempFile, displayName)
        }
    }

    private fun publishToCustomDirectory(tempFile: File, displayName: String, directoryUri: Uri): PublishedOutput {
        val directory = DocumentFile.fromTreeUri(context, directoryUri)
            ?: error("自定义目录不可用")
        val targetFile = directory.createFile("video/mp4", displayName)
            ?: error("无法在自定义目录创建文件")
        context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
            tempFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("无法写入自定义目录")
        return PublishedOutput(label = targetFile.name ?: displayName, uri = targetFile.uri.toString())
    }

    private fun publishToDownloads(tempFile: File, displayName: String): PublishedOutput {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloadsWithMediaStore(tempFile, displayName)
        } else {
            publishToLegacyDownloads(tempFile, displayName)
        }
    }

    private fun publishToDownloadsWithMediaStore(tempFile: File, displayName: String): PublishedOutput {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/liuguang-download")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建 Downloads 输出文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入 Downloads 输出文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        return PublishedOutput(label = "Downloads/liuguang-download/$displayName", uri = uri.toString())
    }

    private fun publishToLegacyDownloads(tempFile: File, displayName: String): PublishedOutput {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "liuguang-download"
        )
        if (!directory.exists()) directory.mkdirs()
        val outputFile = File(directory, displayName)
        tempFile.inputStream().use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }
        return PublishedOutput(label = outputFile.absolutePath, uri = Uri.fromFile(outputFile).toString())
    }
}

data class PublishedOutput(
    val label: String,
    val uri: String
)
