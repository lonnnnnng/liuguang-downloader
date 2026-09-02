package com.liuguang.downloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectMp4ResumeResponseValidatorTest {
    @Test
    fun acceptsMatchingResumeResponse() {
        assertNull(
            DirectMp4ResumeResponseValidator.validationError(
                statusCode = 206,
                contentRange = "bytes 100-999/1000",
                contentLength = 900,
                expectedResumeBytes = 100
            )
        )
        assertEquals(1000L, DirectMp4ResumeResponseValidator.totalBytes("bytes 100-999/1000"))
    }

    @Test
    fun rejectsMissingOrMismatchedResumeRange() {
        assertEquals(
            "MP4 断点响应缺少有效的 Content-Range",
            DirectMp4ResumeResponseValidator.validationError(206, null, 900, 100)
        )
        assertEquals(
            "MP4 断点响应起始位置不匹配",
            DirectMp4ResumeResponseValidator.validationError(206, "bytes 99-999/1000", 901, 100)
        )
        assertEquals(
            "MP4 断点响应长度不匹配",
            DirectMp4ResumeResponseValidator.validationError(206, "bytes 100-999/1000", 899, 100)
        )
    }

    @Test
    fun rejectsInvalidOrUnknownTotalRange() {
        assertEquals(
            "MP4 断点响应范围无效",
            DirectMp4ResumeResponseValidator.validationError(206, "bytes 100-999/999", 900, 100)
        )
        assertEquals(
            "MP4 断点响应缺少有效的 Content-Range",
            DirectMp4ResumeResponseValidator.validationError(206, "bytes 100-999/*", 900, 100)
        )
        assertEquals(
            "服务器未返回 MP4 断点分段（HTTP 200）",
            DirectMp4ResumeResponseValidator.validationError(200, null, 1000, 100)
        )
    }
}
