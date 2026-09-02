package com.liuguang.downloader.data.download

import com.liuguang.downloader.domain.hls.HlsByteRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HlsByteRangeResponseValidatorTest {
    private val range = HlsByteRange(offset = 100, length = 50)

    @Test
    fun acceptsMatchingPartialResponse() {
        assertNull(
            HlsByteRangeResponseValidator.validationError(
                statusCode = 206,
                contentRange = "bytes 100-149/1000",
                contentLength = 50,
                expectedRange = range
            )
        )
    }

    @Test
    fun rejectsFullResponseAndMismatchedHeaders() {
        assertEquals(
            "服务器未按 BYTERANGE 返回分片（HTTP 200）",
            HlsByteRangeResponseValidator.validationError(200, null, 1000, range)
        )
        assertEquals(
            "BYTERANGE 响应范围不匹配",
            HlsByteRangeResponseValidator.validationError(206, "bytes 101-150/1000", 50, range)
        )
        assertEquals(
            "BYTERANGE 响应长度不匹配",
            HlsByteRangeResponseValidator.validationError(206, "bytes 100-149/1000", 49, range)
        )
    }

    @Test
    fun validatesRangeCacheBeforeResumingTask() {
        assertEquals(
            true,
            HlsResourceCacheValidator.isReusable(
                exists = true,
                fileLength = 50,
                expectedRange = range,
                encrypted = false,
                encryptedComplete = true
            )
        )
        assertEquals(
            false,
            HlsResourceCacheValidator.isReusable(
                exists = true,
                fileLength = 49,
                expectedRange = range,
                encrypted = false,
                encryptedComplete = true
            )
        )
        assertEquals(
            true,
            HlsResourceCacheValidator.isReusable(
                exists = true,
                fileLength = 32,
                expectedRange = range,
                encrypted = true,
                encryptedComplete = true
            )
        )
        assertEquals(
            false,
            HlsResourceCacheValidator.isReusable(
                exists = true,
                fileLength = 32,
                expectedRange = range,
                encrypted = true,
                encryptedComplete = false
            )
        )
        assertEquals(
            false,
            HlsResourceCacheValidator.isReusable(
                exists = false,
                fileLength = 50,
                expectedRange = range,
                encrypted = false,
                encryptedComplete = true
            )
        )
    }
}
