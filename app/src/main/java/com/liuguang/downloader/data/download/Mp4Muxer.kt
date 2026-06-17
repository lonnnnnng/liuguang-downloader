package com.liuguang.downloader.data.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

class Mp4Muxer {
    fun muxTsSegmentsToMp4(
        segmentFiles: List<File>,
        outputFile: File,
        onSegmentMuxed: (completed: Int, total: Int) -> Unit
    ) {
        require(segmentFiles.isNotEmpty()) { "没有可合并的分片" }
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val trackFormats = readTrackFormats(segmentFiles.first())
        require(trackFormats.isNotEmpty()) { "无法从分片中读取音视频轨道" }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackByType = trackFormats.associate { descriptor ->
            descriptor.type to muxer.addTrack(descriptor.format)
        }
        val lastPtsByMuxerTrack = mutableMapOf<Int, Long>()
        val buffer = ByteBuffer.allocateDirect(DEFAULT_SAMPLE_BUFFER_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()
        var timelineOffsetUs = 0L

        try {
            muxer.start()
            segmentFiles.forEachIndexed { index, file ->
                val segmentMaxPts = writeSegment(
                    segmentFile = file,
                    muxer = muxer,
                    muxerTrackByType = muxerTrackByType,
                    lastPtsByMuxerTrack = lastPtsByMuxerTrack,
                    buffer = buffer,
                    bufferInfo = bufferInfo,
                    timelineOffsetUs = timelineOffsetUs
                )
                timelineOffsetUs = max(timelineOffsetUs, segmentMaxPts + MIN_SAMPLE_STEP_US)
                onSegmentMuxed(index + 1, segmentFiles.size)
            }
            muxer.stop()
        } catch (error: Throwable) {
            runCatching { muxer.stop() }
            outputFile.delete()
            throw error
        } finally {
            muxer.release()
        }
    }

    private fun readTrackFormats(segmentFile: File): List<TrackDescriptor> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(segmentFile.absolutePath)
            buildList {
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val type = when {
                        mime.startsWith("video/") -> TrackType.Video
                        mime.startsWith("audio/") -> TrackType.Audio
                        else -> null
                    }
                    if (type != null && none { it.type == type }) {
                        add(TrackDescriptor(type = type, format = format))
                    }
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun writeSegment(
        segmentFile: File,
        muxer: MediaMuxer,
        muxerTrackByType: Map<TrackType, Int>,
        lastPtsByMuxerTrack: MutableMap<Int, Long>,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        timelineOffsetUs: Long
    ): Long {
        val extractor = MediaExtractor()
        var segmentFirstPts: Long? = null
        var segmentMaxPts = timelineOffsetUs
        return try {
            extractor.setDataSource(segmentFile.absolutePath)
            val extractorTrackToMuxerTrack = mutableMapOf<Int, Int>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val type = when {
                    mime.startsWith("video/") -> TrackType.Video
                    mime.startsWith("audio/") -> TrackType.Audio
                    else -> null
                }
                val muxerTrack = type?.let { muxerTrackByType[it] }
                if (muxerTrack != null) {
                    extractor.selectTrack(index)
                    extractorTrackToMuxerTrack[index] = muxerTrack
                }
            }

            while (true) {
                val extractorTrack = extractor.sampleTrackIndex
                if (extractorTrack < 0) break
                val muxerTrack = extractorTrackToMuxerTrack[extractorTrack]
                if (muxerTrack == null) {
                    extractor.advance()
                    continue
                }

                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val rawPts = extractor.sampleTime.coerceAtLeast(0L)
                val firstPts = segmentFirstPts ?: rawPts.also { segmentFirstPts = it }
                var adjustedPts = (rawPts - firstPts).coerceAtLeast(0L) + timelineOffsetUs
                val lastPts = lastPtsByMuxerTrack[muxerTrack] ?: -1L
                val minNextPts = lastPts + MIN_SAMPLE_STEP_US
                if (adjustedPts < minNextPts) adjustedPts = minNextPts

                bufferInfo.set(0, sampleSize, adjustedPts, extractor.sampleFlags)
                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                lastPtsByMuxerTrack[muxerTrack] = adjustedPts
                segmentMaxPts = max(segmentMaxPts, adjustedPts)
                extractor.advance()
            }
            segmentMaxPts
        } finally {
            extractor.release()
        }
    }

    private data class TrackDescriptor(
        val type: TrackType,
        val format: MediaFormat
    )

    private enum class TrackType {
        Video,
        Audio
    }

    private companion object {
        private const val DEFAULT_SAMPLE_BUFFER_SIZE = 4 * 1024 * 1024
        private const val MIN_SAMPLE_STEP_US = 1_000L
    }
}
