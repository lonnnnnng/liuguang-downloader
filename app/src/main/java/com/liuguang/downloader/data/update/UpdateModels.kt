package com.liuguang.downloader.data.update

import java.io.File

data class UpdateRelease(
    val versionName: String,
    val title: String,
    val notes: String,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    val sha256: String
)

enum class UpdateStatus {
    Idle,
    Checking,
    UpToDate,
    Available,
    Downloading,
    ReadyToInstall,
    Error
}

data class UpdateUiState(
    val currentVersionName: String,
    val status: UpdateStatus = UpdateStatus.Idle,
    val release: UpdateRelease? = null,
    val downloadProgress: Float = 0f,
    val downloadedFile: File? = null,
    val message: String? = null,
    val installationPromptId: Long = 0L
)

internal object VersionComparator {
    fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = normalize(candidate)
        val currentParts = normalize(current)
        val size = maxOf(candidateParts.size, currentParts.size)
        repeat(size) { index ->
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }

    private fun normalize(version: String): List<Int> {
        return version
            .trim()
            .removePrefix("v")
            .split('.', '-', '_')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
