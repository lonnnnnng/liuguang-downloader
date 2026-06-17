package com.liuguang.downloader.domain.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HlsMasterPlaylistParserTest {
    @Test
    fun choosesHighestResolutionThenBandwidth() {
        val playlist = HlsMasterPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1280x720
                mid/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x1080
                high/index.m3u8
            """.trimIndent(),
            baseUrl = "https://example.com/video/master.m3u8"
        )

        val preferred = playlist.preferredVariant

        assertNotNull(preferred)
        assertEquals("https://example.com/video/high/index.m3u8", preferred?.uri)
        assertEquals(1080, preferred?.height)
    }

    @Test
    fun fallsBackToHighestBandwidthWhenResolutionIsMissing() {
        val playlist = HlsMasterPlaylistParser.parse(
            content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2400000
                high.m3u8
            """.trimIndent()
        )

        assertEquals("high.m3u8", playlist.preferredVariant?.uri)
    }
}
