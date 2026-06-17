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
    fun rejectsByteRangeAndFmp4ForFirstVersion() {
        val byteRangePlaylist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-BYTERANGE:75232@0
                #EXTINF:4.0,
                file.ts
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )
        val fmp4Playlist = HlsMediaPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-MAP:URI="init.mp4"
                #EXTINF:4.0,
                file.m4s
            """.trimIndent(),
            baseUrl = "https://example.com/index.m3u8"
        )

        assertEquals("暂不支持 BYTERANGE 分片", byteRangePlaylist.unsupportedReason)
        assertEquals("暂不支持 fMP4 初始化片段", fmp4Playlist.unsupportedReason)
    }
}
