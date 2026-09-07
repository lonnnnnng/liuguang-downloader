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
    fun acceptsMoreThanTwentyItemsWithoutTruncating() {
        val urls = (1..21).map { "https://example.com/$it/video.mp4" }
        val value = urls.joinToString("\n")

        assertTrue(isSupportedDownloadText(value))
        assertEquals(urls, parseDownloadUrls(value))
    }

    @Test
    fun acceptsOneHundredMixedHlsAndMp4ItemsInOrder() {
        val urls = (1..100).map { index ->
            val resource = if (index % 2 == 0) "index.m3u8" else "video.mp4"
            "https://example.com/$index/$resource"
        }
        val value = urls.joinToString("\n")

        assertTrue(isSupportedDownloadText(value))
        assertEquals(urls, parseDownloadUrls(value))
    }

    @Test
    fun rejectsUnsupportedUrlAfterTheFormerBatchLimit() {
        val supportedUrls = (1..20).map { "https://example.com/$it/video.mp4" }
        val value = (supportedUrls + "https://example.com/page.html").joinToString("\n")

        assertFalse(isSupportedDownloadText(value))
    }

    @Test
    fun rejectsEmptyBatch() {
        assertFalse(isSupportedDownloadText(""))
        assertFalse(isSupportedDownloadText(" \n\t\n- "))
    }

    @Test
    fun preservesTotalInputLengthLimit() {
        val value = (1..100).joinToString("\n") { "https://example.com/$it/video.mp4" }
            .padEnd(65_536, ' ')

        assertTrue(isSupportedDownloadText(value))
        assertFalse(isSupportedDownloadText(value + " "))
    }

    @Test
    fun preservesIndividualUrlLengthLimit() {
        val value = "https://example.com/video.mp4?token=".padEnd(8_192, 'a')

        assertTrue(isSupportedDownloadText(value))
        assertFalse(isSupportedDownloadText(value + "a"))
    }
}
