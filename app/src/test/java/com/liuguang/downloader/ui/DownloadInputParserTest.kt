package com.liuguang.downloader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadInputParserTest {
    @Test
    fun parsesOneUrlPerLineAndRemovesDuplicates() {
        val urls = parseDownloadUrls(
            """
                https://example.com/a/index.m3u8
                - https://example.com/b/video.mp4
                https://example.com/a/index.m3u8
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://example.com/a/index.m3u8",
                "https://example.com/b/video.mp4"
            ),
            urls
        )
        assertTrue(isSupportedDownloadText(urls.joinToString("\n")))
    }

    @Test
    fun rejectsBatchWhenAnyUrlIsUnsupported() {
        assertFalse(
            isSupportedDownloadText(
                "https://example.com/a/index.m3u8\nhttps://example.com/page.html"
            )
        )
    }

    @Test
    fun rejectsMoreThanTwentyItems() {
        val value = (1..21).joinToString("\n") { "https://example.com/$it/video.mp4" }

        assertFalse(isSupportedDownloadText(value))
    }
}
