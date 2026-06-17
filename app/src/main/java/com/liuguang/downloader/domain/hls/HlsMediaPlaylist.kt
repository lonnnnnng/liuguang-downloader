package com.liuguang.downloader.domain.hls

import java.net.URI

data class HlsSegment(
    val uri: String,
    val durationSeconds: Double?,
    val sequence: Int,
    val encryptionKey: HlsEncryptionKey? = null
)

data class HlsEncryptionKey(
    val method: String,
    val uri: String,
    val ivHex: String?
)

data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val hasEncryptedSegments: Boolean,
    val hasUnsupportedEncryption: Boolean,
    val hasMissingEncryptionKeyUri: Boolean,
    val hasByteRanges: Boolean,
    val hasFmp4Map: Boolean
) {
    val isSupportedForFirstVersion: Boolean
        get() = segments.isNotEmpty() &&
            !hasUnsupportedEncryption &&
            !hasMissingEncryptionKeyUri &&
            !hasByteRanges &&
            !hasFmp4Map

    val unsupportedReason: String?
        get() = when {
            hasUnsupportedEncryption -> "暂不支持该加密方式"
            hasMissingEncryptionKeyUri -> "加密 m3u8 缺少密钥地址"
            hasByteRanges -> "暂不支持 BYTERANGE 分片"
            hasFmp4Map -> "暂不支持 fMP4 初始化片段"
            segments.isEmpty() -> "没有解析到可下载分片"
            else -> null
        }
}

object HlsMediaPlaylistParser {
    fun parse(content: String, baseUrl: String): HlsMediaPlaylist {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val segments = mutableListOf<HlsSegment>()
        var pendingDurationSeconds: Double? = null
        var mediaSequence = 0
        var currentEncryptionKey: HlsEncryptionKey? = null
        var hasEncryptedSegments = false
        var hasUnsupportedEncryption = false
        var hasMissingEncryptionKeyUri = false
        var hasByteRanges = false
        var hasFmp4Map = false

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingDurationSeconds = line.substringAfter(':')
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull()
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val method = attributes["METHOD"].orEmpty().uppercase()
                    when (method) {
                        "NONE" -> currentEncryptionKey = null
                        "AES-128" -> {
                            hasEncryptedSegments = true
                            val keyUri = attributes["URI"]
                            if (keyUri.isNullOrBlank()) {
                                hasMissingEncryptionKeyUri = true
                                currentEncryptionKey = null
                            } else {
                                currentEncryptionKey = HlsEncryptionKey(
                                    method = method,
                                    uri = resolveUri(baseUrl, keyUri),
                                    ivHex = attributes["IV"]
                                )
                            }
                        }
                        else -> {
                            hasEncryptedSegments = true
                            hasUnsupportedEncryption = true
                            currentEncryptionKey = null
                        }
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    hasByteRanges = true
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    hasFmp4Map = true
                }
                line.startsWith("#") -> Unit
                else -> {
                    segments += HlsSegment(
                        uri = resolveUri(baseUrl, line),
                        durationSeconds = pendingDurationSeconds,
                        sequence = mediaSequence + segments.size,
                        encryptionKey = currentEncryptionKey
                    )
                    pendingDurationSeconds = null
                }
            }
        }

        return HlsMediaPlaylist(
            segments = segments,
            hasEncryptedSegments = hasEncryptedSegments,
            hasUnsupportedEncryption = hasUnsupportedEncryption,
            hasMissingEncryptionKeyUri = hasMissingEncryptionKeyUri,
            hasByteRanges = hasByteRanges,
            hasFmp4Map = hasFmp4Map
        )
    }

    private fun parseAttributeList(value: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        var index = 0
        while (index < value.length) {
            val keyStart = index
            while (index < value.length && value[index] != '=') index++
            if (index >= value.length) break
            val key = value.substring(keyStart, index).trim().uppercase()
            index++

            val rawValue = buildString {
                var quoted = false
                if (index < value.length && value[index] == '"') {
                    quoted = true
                    index++
                }
                while (index < value.length) {
                    val char = value[index]
                    if (quoted && char == '"') {
                        index++
                        break
                    }
                    if (!quoted && char == ',') break
                    append(char)
                    index++
                }
            }.trim()
            if (key.isNotEmpty()) attributes[key] = rawValue
            while (index < value.length && value[index] != ',') index++
            if (index < value.length && value[index] == ',') index++
        }
        return attributes
    }

    private fun resolveUri(baseUrl: String, uri: String): String {
        return runCatching { URI(baseUrl).resolve(uri).toString() }.getOrDefault(uri)
    }
}
