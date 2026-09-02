package com.liuguang.downloader.domain.hls

import java.net.URI

data class HlsSegment(
    val uri: String,
    val durationSeconds: Double?,
    val sequence: Int,
    val encryptionKey: HlsEncryptionKey? = null,
    val byteRange: HlsByteRange? = null,
    val initializationSection: HlsInitializationSection? = null
)

data class HlsByteRange(
    val offset: Long,
    val length: Long
) {
    val endInclusive: Long
        get() = offset + length - 1L
}

data class HlsInitializationSection(
    val uri: String,
    val byteRange: HlsByteRange? = null,
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
    val hasFmp4Map: Boolean,
    val hasDiscontinuity: Boolean,
    val parsingError: String? = null
) {
    val totalDurationSeconds: Double
        get() = segments.sumOf { it.durationSeconds ?: 0.0 }

    val initializationSections: List<HlsInitializationSection>
        get() = segments.mapNotNull(HlsSegment::initializationSection).distinct()

    val isSupportedForFirstVersion: Boolean
        get() = segments.isNotEmpty() &&
            !hasUnsupportedEncryption &&
            !hasMissingEncryptionKeyUri &&
            parsingError == null &&
            initializationSections.size <= 1 &&
            !(hasFmp4Map && hasDiscontinuity) &&
            !(hasFmp4Map && segments.any { it.initializationSection == null }) &&
            initializationSections.none { section ->
                section.encryptionKey != null && section.encryptionKey.ivHex.isNullOrBlank()
            }

    val unsupportedReason: String?
        get() = when {
            hasUnsupportedEncryption -> "暂不支持该加密方式"
            hasMissingEncryptionKeyUri -> "加密 m3u8 缺少密钥地址"
            parsingError != null -> parsingError
            initializationSections.size > 1 -> "暂不支持下载过程中切换 fMP4 初始化片段"
            hasFmp4Map && hasDiscontinuity -> "暂不支持含不连续点的 fMP4 分片"
            hasFmp4Map && segments.any { it.initializationSection == null } -> "暂不支持混合 TS 与 fMP4 分片"
            initializationSections.any { section ->
                section.encryptionKey != null && section.encryptionKey.ivHex.isNullOrBlank()
            } -> "AES-128 加密的 fMP4 初始化片段缺少 IV"
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
        var hasDiscontinuity = false
        var parsingError: String? = null
        var pendingByteRange: String? = null
        var currentInitializationSection: HlsInitializationSection? = null
        var previousSegmentRangeUri: String? = null
        var previousSegmentRangeEndExclusive: Long? = null
        var previousMapRangeUri: String? = null
        var previousMapRangeEndExclusive: Long? = null

        fun recordParsingError(message: String) {
            if (parsingError == null) parsingError = message
        }

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
                    if (pendingByteRange != null) {
                        recordParsingError("BYTERANGE 后缺少对应分片地址")
                    }
                    pendingByteRange = line.substringAfter(':').trim()
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    hasFmp4Map = true
                    val attributes = parseAttributeList(line.substringAfter(':'))
                    val mapUri = attributes["URI"]
                    if (mapUri.isNullOrBlank()) {
                        recordParsingError("fMP4 初始化片段缺少 URI")
                        currentInitializationSection = null
                    } else {
                        val resolvedMapUri = resolveUri(baseUrl, mapUri)
                        val mapRangeText = attributes["BYTERANGE"]
                        val mapRange = mapRangeText?.let { value ->
                            parseByteRange(
                                value = value,
                                implicitOffset = previousMapRangeEndExclusive
                                    ?.takeIf { previousMapRangeUri == resolvedMapUri },
                                errorPrefix = "fMP4 初始化片段 BYTERANGE",
                                onError = ::recordParsingError
                            )
                        }
                        if (mapRangeText == null || mapRange != null) {
                            currentInitializationSection = HlsInitializationSection(
                                uri = resolvedMapUri,
                                byteRange = mapRange,
                                encryptionKey = currentEncryptionKey
                            )
                        }
                        if (mapRange != null) {
                            previousMapRangeUri = resolvedMapUri
                            previousMapRangeEndExclusive = mapRange.endInclusive + 1L
                        } else {
                            previousMapRangeUri = null
                            previousMapRangeEndExclusive = null
                        }
                    }
                }
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    // long: TS 沿用既有合并行为；fMP4 暂无跨 period 重建轨道和时间线的能力，稍后统一拒绝。
                    hasDiscontinuity = true
                }
                line.startsWith("#") -> Unit
                else -> {
                    val resolvedSegmentUri = resolveUri(baseUrl, line)
                    val rangeText = pendingByteRange
                    val segmentRange = rangeText?.let { value ->
                        parseByteRange(
                            value = value,
                            implicitOffset = previousSegmentRangeEndExclusive
                                ?.takeIf { previousSegmentRangeUri == resolvedSegmentUri },
                            errorPrefix = "分片 BYTERANGE",
                            onError = ::recordParsingError
                        )
                    }
                    segments += HlsSegment(
                        uri = resolvedSegmentUri,
                        durationSeconds = pendingDurationSeconds,
                        sequence = mediaSequence + segments.size,
                        encryptionKey = currentEncryptionKey,
                        byteRange = segmentRange,
                        initializationSection = currentInitializationSection
                    )
                    if (segmentRange != null) {
                        previousSegmentRangeUri = resolvedSegmentUri
                        previousSegmentRangeEndExclusive = segmentRange.endInclusive + 1L
                    } else {
                        previousSegmentRangeUri = null
                        previousSegmentRangeEndExclusive = null
                    }
                    pendingDurationSeconds = null
                    pendingByteRange = null
                }
            }
        }

        if (pendingByteRange != null) {
            recordParsingError("BYTERANGE 后缺少对应分片地址")
        }

        return HlsMediaPlaylist(
            segments = segments,
            hasEncryptedSegments = hasEncryptedSegments,
            hasUnsupportedEncryption = hasUnsupportedEncryption,
            hasMissingEncryptionKeyUri = hasMissingEncryptionKeyUri,
            hasByteRanges = hasByteRanges,
            hasFmp4Map = hasFmp4Map,
            hasDiscontinuity = hasDiscontinuity,
            parsingError = parsingError
        )
    }

    private fun parseByteRange(
        value: String,
        implicitOffset: Long?,
        errorPrefix: String,
        onError: (String) -> Unit
    ): HlsByteRange? {
        val parts = value.trim().split('@', limit = 2)
        val length = parts.firstOrNull()?.trim()?.toLongOrNull()
        if (length == null || length <= 0L) {
            onError("$errorPrefix 长度无效")
            return null
        }
        // long: HLS 只允许从同一资源的上一段连续推导隐式偏移，无法证明连续时必须拒绝整个列表。
        val offset = if (parts.size == 2) {
            parts[1].trim().toLongOrNull()
        } else {
            implicitOffset
        }
        if (offset == null || offset < 0L) {
            onError("$errorPrefix 缺少可推导的起始偏移")
            return null
        }
        if (offset > Long.MAX_VALUE - length) {
            onError("$errorPrefix 超出支持范围")
            return null
        }
        return HlsByteRange(offset = offset, length = length)
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
