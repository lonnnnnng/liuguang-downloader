package com.liuguang.downloader.domain.hls

import java.net.URI

data class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val averageBandwidth: Long?,
    val width: Int?,
    val height: Int?
) {
    val displayName: String
        get() {
            val resolution = height?.let { "${it}p" }
            val speed = bandwidth.takeIf { it > 0L }?.let { "${it / 1_000} kbps" }
            return listOfNotNull(resolution, speed).ifEmpty { listOf("自动") }.joinToString(" · ")
        }
}

data class HlsMasterPlaylist(
    val variants: List<HlsVariant>
) {
    val preferredVariant: HlsVariant?
        get() = variants.maxWithOrNull(
            compareBy<HlsVariant> { variant ->
                (variant.width ?: 0) * (variant.height ?: 0)
            }.thenBy { variant ->
                variant.bandwidth
            }
        )
}

object HlsMasterPlaylistParser {
    private val attributePattern = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun parse(content: String, baseUrl: String? = null): HlsMasterPlaylist {
        val lines = content
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) return@forEachIndexed

                val attributes = parseAttributes(line.substringAfter(':'))
                val uriLine = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                val resolution = attributes["RESOLUTION"]?.split('x', limit = 2).orEmpty()
                val width = resolution.getOrNull(0)?.toIntOrNull()
                val height = resolution.getOrNull(1)?.toIntOrNull()

                add(
                    HlsVariant(
                        uri = resolveUri(baseUrl, uriLine),
                        bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        averageBandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                        width = width,
                        height = height
                    )
                )
            }
        }

        return HlsMasterPlaylist(variants = variants)
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        return attributePattern.findAll(raw).associate { match ->
            val key = match.groupValues[1].uppercase()
            val value = match.groupValues[2].trim().removeSurrounding("\"")
            key to value
        }
    }

    private fun resolveUri(baseUrl: String?, uri: String): String {
        if (baseUrl.isNullOrBlank()) return uri
        return runCatching { URI(baseUrl).resolve(uri).toString() }.getOrDefault(uri)
    }
}
