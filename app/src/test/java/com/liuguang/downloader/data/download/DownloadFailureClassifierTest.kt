package com.liuguang.downloader.data.download

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureClassifierTest {
    @Test
    fun retriesTemporaryServerAndNetworkFailures() {
        val server = DownloadFailureClassifier.classify(DownloadHttpException("分片下载失败", 503))
        val timeout = DownloadFailureClassifier.classify(SocketTimeoutException("timeout"))

        assertEquals(DownloadFailureCategory.Server, server.category)
        assertTrue(server.retryable)
        assertEquals(DownloadFailureCategory.Network, timeout.category)
        assertTrue(timeout.retryable)
    }

    @Test
    fun doesNotRetryAccessOrExpiredLinks() {
        val forbidden = DownloadFailureClassifier.classify(DownloadHttpException("m3u8 请求失败", 403))
        val missing = DownloadFailureClassifier.classify(DownloadHttpException("m3u8 请求失败", 404))

        assertEquals(DownloadFailureCategory.AccessDenied, forbidden.category)
        assertFalse(forbidden.retryable)
        assertEquals(DownloadFailureCategory.LinkExpired, missing.category)
        assertFalse(missing.retryable)
    }

    @Test
    fun categorizesUnsupportedAndMuxFailures() {
        val unsupported = DownloadFailureClassifier.classify(IllegalStateException("暂不支持 BYTERANGE 分片"))
        val muxing = DownloadFailureClassifier.classify(
            DownloadMuxException("MP4 合并失败：无法读取音视频轨道", IllegalStateException())
        )

        assertEquals(DownloadFailureCategory.Unsupported, unsupported.category)
        assertFalse(unsupported.retryable)
        assertEquals(DownloadFailureCategory.Muxing, muxing.category)
        assertFalse(muxing.retryable)
    }
}
