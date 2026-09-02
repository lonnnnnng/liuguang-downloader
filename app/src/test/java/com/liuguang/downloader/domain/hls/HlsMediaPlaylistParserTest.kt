package com.liuguang.downloader.domain.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsMediaPlaylistParserTest {
    @Test
    fun parsesSegmentsAgainstBaseUrl() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXTINF:4.0,
                seg000.ts
                #EXTINF:4.0,
                nested/seg001.ts
            """.trimIndent(),
            baseUrl = "https://example.com/path/index.m3u8"
        )

        assertTrue(playlist.isSupportedForFirstVersion)
        assertEquals(2, playlist.segments.size)
        assertEquals(8.0, playlist.totalDurationSeconds, 0.001)
        assertEquals("https://example.com/path/seg000.ts", playlist.segments[0].uri)
        assertEquals("https://example.com/path/nested/seg001.ts", playlist.segments[1].uri)
    }

    @Test
    fun parsesAes128EncryptedSegments() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-MEDIA-SEQUENCE:7
                #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin",IV=0x00000000000000000000000000000009
                #EXTINF:4.0,
                seg000.ts
                #EXT-X-KEY:METHOD=NONE
                #EXTINF:4.0,
                seg001.ts
            """.trimIndent(),
            baseUrl = "https://example.com/path/index.m3u8"
        )

        assertTrue(playlist.isSupportedForFirstVersion)
        assertTrue(playlist.hasEncryptedSegments)
        assertEquals(2, playlist.segments.size)
        assertEquals(7, playlist.segments[0].sequence)
        assertEquals("https://example.com/path/keys/key.bin", playlist.segments[0].encryptionKey?.uri)
        assertEquals("0x00000000000000000000000000000009", playlist.segments[0].encryptionKey?.ivHex)
        assertEquals(8, playlist.segments[1].sequence)
        assertNull(playlist.segments[1].encryptionKey)
    }

    @Test
    fun rejectsUnsupportedEncryptionMethods() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin"
                #EXTINF:4.0,
                seg000.ts
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertFalse(playlist.isSupportedForFirstVersion)
        assertEquals("暂不支持该加密方式", playlist.unsupportedReason)
    }

    @Test
    fun parsesExplicitAndImplicitByteRanges() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-BYTERANGE:75232@0
                #EXTINF:4.0,
                file.ts
                #EXT-X-BYTERANGE:11264
                #EXTINF:4.0,
                file.ts
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertTrue(playlist.isSupportedForFirstVersion)
        assertTrue(playlist.hasByteRanges)
        assertEquals(HlsByteRange(offset = 0, length = 75232), playlist.segments[0].byteRange)
        assertEquals(HlsByteRange(offset = 75232, length = 11264), playlist.segments[1].byteRange)
    }

    @Test
    fun parsesFmp4MapAndItsByteRange() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-MAP:BYTERANGE="1024@16",URI="init.mp4"
                #EXTINF:4.0,
                file-1.m4s
                #EXTINF:4.0,
                file-2.m4s
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertTrue(playlist.isSupportedForFirstVersion)
        assertTrue(playlist.hasFmp4Map)
        assertEquals(1, playlist.initializationSections.size)
        assertEquals("https://example.com/init.mp4", playlist.initializationSections.single().uri)
        assertEquals(HlsByteRange(offset = 16, length = 1024), playlist.initializationSections.single().byteRange)
        assertEquals(playlist.initializationSections.single(), playlist.segments[0].initializationSection)
        assertEquals(playlist.initializationSections.single(), playlist.segments[1].initializationSection)
    }

    @Test
    fun rejectsImplicitRangeWithoutPreviousRangeForSameResource() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-BYTERANGE:100
                #EXTINF:4.0,
                file.ts
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertFalse(playlist.isSupportedForFirstVersion)
        assertEquals("分片 BYTERANGE 缺少可推导的起始偏移", playlist.unsupportedReason)
    }

    @Test
    fun rejectsEncryptedFmp4MapWithoutIv() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.0,
                file.m4s
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertFalse(playlist.isSupportedForFirstVersion)
        assertEquals("AES-128 加密的 fMP4 初始化片段缺少 IV", playlist.unsupportedReason)
    }

    @Test
    fun rejectsDiscontinuityInFmp4Playlist() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.0,
                file-1.m4s
                #EXT-X-DISCONTINUITY
                #EXTINF:4.0,
                file-2.m4s
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertTrue(playlist.hasDiscontinuity)
        assertFalse(playlist.isSupportedForFirstVersion)
        assertEquals("暂不支持含不连续点的 fMP4 分片", playlist.unsupportedReason)
    }

    @Test
    fun keepsExistingTsBehaviorForDiscontinuity() {
        val playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXTINF:4.0,
                file-1.ts
                #EXT-X-DISCONTINUITY
                #EXTINF:4.0,
                file-2.ts
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertTrue(playlist.hasDiscontinuity)
        assertTrue(playlist.isSupportedForFirstVersion)
        assertNull(playlist.unsupportedReason)
    }
}
