package com.liuguang.downloader.data.download

import com.liuguang.downloader.domain.hls.HlsByteRange

internal object HlsByteRangeResponseValidator {
    fun validationError(
        statusCode: Int,
        contentRange: String?,
        contentLength: Long,
        expectedRange: HlsByteRange
    ): String? {
        // long: 服务器返回 200 代表忽略 Range，若继续写入会把完整资源误当成单个分片并生成损坏视频。
        if (statusCode != HTTP_PARTIAL_CONTENT) {
            return "服务器未按 BYTERANGE 返回分片（HTTP $statusCode）"
        }
        val match = contentRange?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: return "BYTERANGE 响应缺少有效的 Content-Range"
        val actualStart = match.groupValues[1].toLongOrNull()
        val actualEnd = match.groupValues[2].toLongOrNull()
        if (actualStart != expectedRange.offset || actualEnd != expectedRange.endInclusive) {
            return "BYTERANGE 响应范围不匹配"
        }
        if (contentLength >= 0L && contentLength != expectedRange.length) {
            return "BYTERANGE 响应长度不匹配"
        }
        return null
    }

    private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(?:\d+|\*)""", RegexOption.IGNORE_CASE)
    private const val HTTP_PARTIAL_CONTENT = 206
}

internal object HlsResourceCacheValidator {
    fun isReusable(
        exists: Boolean,
        fileLength: Long,
        expectedRange: HlsByteRange?,
        encrypted: Boolean,
        encryptedComplete: Boolean
    ): Boolean {
        if (!exists || fileLength <= 0L) return false
        if (encrypted && !encryptedComplete) return false
        // long: 加密资源解密后会去掉 padding，只有未加密 Range 文件才能按网络声明长度校验缓存。
        return expectedRange == null || encrypted || fileLength == expectedRange.length
    }
}

internal object DirectMp4ResumeResponseValidator {
    fun validationError(
        statusCode: Int,
        contentRange: String?,
        contentLength: Long,
        expectedResumeBytes: Long
    ): String? {
        if (statusCode != HTTP_PARTIAL_CONTENT) {
            return "服务器未返回 MP4 断点分段（HTTP $statusCode）"
        }
        val match = contentRange?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: return "MP4 断点响应缺少有效的 Content-Range"
        val start = match.groupValues[1].toLongOrNull()
            ?: return "MP4 断点响应起始位置无效"
        val end = match.groupValues[2].toLongOrNull()
            ?: return "MP4 断点响应结束位置无效"
        val total = match.groupValues[3].toLongOrNull()
            ?: return "MP4 断点响应总大小无效"
        if (start != expectedResumeBytes) return "MP4 断点响应起始位置不匹配"
        if (end < start || total <= end) return "MP4 断点响应范围无效"
        val rangeLength = end - start + 1L
        if (contentLength >= 0L && contentLength != rangeLength) {
            return "MP4 断点响应长度不匹配"
        }
        return null
    }

    fun totalBytes(contentRange: String?): Long? {
        return contentRange
            ?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?.groupValues
            ?.get(3)
            ?.toLongOrNull()
    }

    private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
    private const val HTTP_PARTIAL_CONTENT = 206
}
