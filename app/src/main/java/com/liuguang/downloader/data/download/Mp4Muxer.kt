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
        muxMediaSourcesToMp4(
            mediaFiles = segmentFiles,
            outputFile = outputFile,
            onSourceMuxed = onSegmentMuxed
        )
    }

    fun muxFmp4SegmentsToMp4(
        initializationFile: File,
        segmentFiles: List<File>,
        outputFile: File,
        onSegmentMuxed: (completed: Int, total: Int) -> Unit
    ) {
        require(initializationFile.exists() && initializationFile.length() > 0L) { "fMP4 初始化片段为空" }
        require(segmentFiles.isNotEmpty()) { "没有可合并的 fMP4 分片" }
        val sourceFile = File(outputFile.parentFile, ".${outputFile.name}.fragmented-source.mp4")
        if (sourceFile.exists()) sourceFile.delete()

        try {
            sourceFile.outputStream().use { output ->
                initializationFile.inputStream().use { input -> input.copyTo(output) }
                segmentFiles.forEachIndexed { index, segmentFile ->
                    require(segmentFile.exists() && segmentFile.length() > 0L) { "fMP4 分片为空" }
                    segmentFile.inputStream().use { input -> input.copyTo(output) }
                    onSegmentMuxed(index + 1, segmentFiles.size)
                }
            }
            // long: init 与 m4s 先还原成完整 fragmented MP4，让平台 Extractor 取得轨道配置，再输出普通 MP4。
            muxMediaSourcesToMp4(
                mediaFiles = listOf(sourceFile),
                outputFile = outputFile,
                onSourceMuxed = { _, _ -> Unit }
            )
        } finally {
            sourceFile.delete()
        }
    }

    private fun muxMediaSourcesToMp4(
        mediaFiles: List<File>,
        outputFile: File,
        onSourceMuxed: (completed: Int, total: Int) -> Unit
    ) {
        require(mediaFiles.isNotEmpty()) { "没有可合并的分片" }
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val trackFormats = readTrackFormats(mediaFiles.first())
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
            mediaFiles.forEachIndexed { index, file ->
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
                onSourceMuxed(index + 1, mediaFiles.size)
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

                bufferInfo.set(0, sampleSize, adjustedPts, extractor.sampleFlags.toCodecBufferFlags())
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

    private fun Int.toCodecBufferFlags(): Int {
        var flags = 0
        // long: MediaExtractor 和 MediaCodec 的 flag 数值不完全一致，写入 muxer 前只保留 MP4 样本边界需要的标记。
        if (this and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (this and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private companion object {
        private const val DEFAULT_SAMPLE_BUFFER_SIZE = 4 * 1024 * 1024
        private const val MIN_SAMPLE_STEP_US = 1_000L
    }
}
