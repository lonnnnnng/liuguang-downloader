package com.liuguang.downloader.data.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.liuguang.downloader.domain.hls.HlsByteRange
import com.liuguang.downloader.domain.hls.HlsEncryptionKey
import com.liuguang.downloader.domain.hls.HlsInitializationSection
import com.liuguang.downloader.domain.hls.HlsMasterPlaylistParser
import com.liuguang.downloader.domain.hls.HlsMediaPlaylist
import com.liuguang.downloader.domain.hls.HlsMediaPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

class M3u8DownloadEngine(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val muxer: Mp4Muxer = Mp4Muxer(),
    private val outputWriter: DownloadOutputWriter = DownloadOutputWriter(context)
) {
    private val activeCallsByTask = ConcurrentHashMap<String, MutableSet<Call>>()

    fun workDirectoryForTask(taskId: String): File {
        return File(context.cacheDir, "hls-downloads/$taskId")
    }

    fun cancelTaskRequests(taskId: String) {
        activeCallsByTask.remove(taskId)?.forEach(Call::cancel)
    }

    fun clearTaskCache(taskId: String) {
        cancelTaskRequests(taskId)
        workDirectoryForTask(taskId).deleteRecursively()
    }

    suspend fun preflight(
        url: String,
        fileNameHint: String,
        customDirectoryUri: Uri?
    ): DownloadPreflightResult = withContext(Dispatchers.IO) {
        val taskId = "preflight-${UUID.randomUUID()}"
        val requestedDisplayName = buildMp4DisplayName(fileNameHint)
        val directMp4 = isDirectMp4Url(url)
        var fmp4Hls = false
        val expectedBytes = if (directMp4) {
            fetchContentLength(taskId, url)
        } else {
            val firstPlaylistText = fetchText(taskId, url)
            val masterPlaylist = HlsMasterPlaylistParser.parse(firstPlaylistText, url)
            val mediaUrl = masterPlaylist.preferredVariant?.uri ?: url
            val mediaPlaylistText = if (mediaUrl == url) firstPlaylistText else fetchText(taskId, mediaUrl)
            val mediaPlaylist = HlsMediaPlaylistParser.parse(mediaPlaylistText, mediaUrl)
            mediaPlaylist.unsupportedReason?.let { error(it) }
            fmp4Hls = mediaPlaylist.initializationSections.isNotEmpty()
            estimateHlsBytes(
                taskId = taskId,
                mediaPlaylist = mediaPlaylist,
                bandwidth = masterPlaylist.preferredVariant?.averageBandwidth
                    ?: masterPlaylist.preferredVariant?.bandwidth
            )
        }
        val availableBytes = outputWriter.availableBytes(customDirectoryUri)
        val requiredBytes = expectedBytes.takeIf { it > 0L }
            ?.let {
                DownloadStorageRequirementCalculator.calculate(
                    expectedBytes = it,
                    directMp4 = directMp4,
                    fmp4Hls = fmp4Hls
                )
            }
            ?: UNKNOWN_TOTAL_BYTES
        val resolvedDisplayName = outputWriter.resolveUniqueDisplayName(requestedDisplayName, customDirectoryUri)
        val storageSufficient = requiredBytes <= 0L || availableBytes <= 0L || availableBytes >= requiredBytes
        DownloadPreflightResult(
            displayName = resolvedDisplayName,
            renamedBecauseConflict = resolvedDisplayName != requestedDisplayName,
            expectedBytes = expectedBytes,
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
            storageSufficient = storageSufficient
        )
    }

    fun download(
        taskId: String,
        url: String,
        fileNameHint: String,
        customDirectoryUri: Uri?,
        downloadThreadCount: Int
    ): Flow<DownloadProgress> = channelFlow {
        val requestedDisplayName = buildMp4DisplayName(fileNameHint)
        var displayName = requestedDisplayName
        val workDir = workDirectoryForTask(taskId)
        val segmentDir = File(workDir, "segments")
        var tempMp4 = File(workDir, displayName)
        segmentDir.mkdirs()
        var completedSuccessfully = false

        try {
            if (isDirectMp4Url(url)) {
                send(DownloadProgress.Preparing("正在检查 MP4 大小和存储空间"))
                val expectedBytes = fetchContentLength(taskId, url)
                displayName = prepareOutputNameAndStorage(
                    requestedDisplayName = requestedDisplayName,
                    expectedBytes = expectedBytes,
                    customDirectoryUri = customDirectoryUri
                )
                tempMp4 = File(workDir, displayName)
                downloadDirectMp4(
                    taskId = taskId,
                    url = url,
                    displayName = displayName,
                    workDir = workDir,
                    tempMp4 = tempMp4,
                    customDirectoryUri = customDirectoryUri
                )
                completedSuccessfully = true
                return@channelFlow
            }

            send(DownloadProgress.Preparing("正在读取 m3u8"))
            val firstPlaylistText = fetchText(taskId, url)
            val masterPlaylist = HlsMasterPlaylistParser.parse(firstPlaylistText, url)
            val mediaUrl = masterPlaylist.preferredVariant?.uri ?: url
            masterPlaylist.preferredVariant?.let { variant ->
                send(DownloadProgress.VariantSelected(variant.displayName, mediaUrl))
            }

            send(DownloadProgress.Preparing("正在解析分片列表"))
            val mediaPlaylistText = if (mediaUrl == url) firstPlaylistText else fetchText(taskId, mediaUrl)
            val mediaPlaylist = HlsMediaPlaylistParser.parse(mediaPlaylistText, mediaUrl)
            mediaPlaylist.unsupportedReason?.let { reason ->
                error(reason)
            }
            val initializationSection = mediaPlaylist.initializationSections.singleOrNull()
            val isFmp4 = initializationSection != null

            val estimatedBytes = estimateHlsBytes(
                taskId = taskId,
                mediaPlaylist = mediaPlaylist,
                bandwidth = masterPlaylist.preferredVariant?.averageBandwidth
                    ?: masterPlaylist.preferredVariant?.bandwidth
            )
            displayName = prepareOutputNameAndStorage(
                requestedDisplayName = requestedDisplayName,
                expectedBytes = estimatedBytes,
                customDirectoryUri = customDirectoryUri,
                directMp4 = false,
                fmp4Hls = isFmp4
            )
            tempMp4 = File(workDir, displayName)
            if (displayName != requestedDisplayName) {
                send(DownloadProgress.Preparing("检测到同名文件，将保存为 $displayName"))
            }

            val totalSegments = mediaPlaylist.segments.size
            val startedAtMillis = SystemClock.elapsedRealtime()
            val segmentExtension = if (isFmp4) ".m4s" else ".ts"
            val segmentFiles = List(totalSegments) { index ->
                File(segmentDir, index.toString().padStart(5, '0') + segmentExtension)
            }
            mediaPlaylist.segments.forEachIndexed { index, segment ->
                if (!isReusableHlsResource(segmentFiles[index], segment.byteRange, segment.encryptionKey)) {
                    segmentFiles[index].delete()
                }
            }
            val existingSegments = segmentFiles.map { it.exists() && it.length() > 0L }
            val initializationFile = initializationSection?.let { File(workDir, "initialization.mp4") }
            if (
                initializationSection != null && initializationFile != null &&
                !isReusableHlsResource(
                    initializationFile,
                    initializationSection.byteRange,
                    initializationSection.encryptionKey
                )
            ) {
                initializationFile.delete()
            }
            val resumedBytes = segmentFiles
                .asSequence()
                .filter(File::exists)
                .sumOf(File::length) + (initializationFile?.takeIf(File::exists)?.length() ?: 0L)
            val initialCompletedSegments = existingSegments.count { it }
            val downloadedBytes = AtomicLong(resumedBytes)
            val completedSegments = AtomicInteger(initialCompletedSegments)
            val keyCache = ConcurrentHashMap<String, ByteArray>()
            val semaphore = Semaphore(downloadThreadCount.coerceAtLeast(1))

            if (initializationSection != null && initializationFile != null && !initializationFile.exists()) {
                send(DownloadProgress.Preparing("正在下载 fMP4 初始化片段"))
                downloadHlsResource(
                    taskId = taskId,
                    uri = initializationSection.uri,
                    byteRange = initializationSection.byteRange,
                    outputFile = initializationFile,
                    encryptionKey = initializationSection.encryptionKey,
                    sequence = 0,
                    keyCache = keyCache,
                    onBytesCopied = downloadedBytes::addAndGet
                )
            }

            if (initialCompletedSegments > 0) {
                send(
                    DownloadProgress.Preparing(
                        "检测到已下载 ${initialCompletedSegments}/${totalSegments} 个分片，继续下载"
                    )
                )
            }

            coroutineScope {
                val progressTicker = launch {
                    while (isActive) {
                        delay(PROGRESS_TICK_INTERVAL_MILLIS)
                        val completed = completedSegments.get()
                        if (completed >= totalSegments) break
                        trySend(
                            DownloadProgress.SegmentProgress(
                                completedSegments = completed,
                                totalSegments = totalSegments,
                                downloadedBytes = downloadedBytes.get(),
                                speedBytesPerSecond = calculateSpeed(downloadedBytes.get(), startedAtMillis),
                                elapsedMillis = elapsedSince(startedAtMillis)
                            )
                        )
                    }
                }
                mediaPlaylist.segments.mapIndexed { index, segment ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            coroutineContext.ensureActive()
                            val outputFile = segmentFiles[index]
                            if (outputFile.exists() && outputFile.length() > 0L) {
                                return@withPermit
                            }
                            downloadHlsResource(
                                taskId = taskId,
                                uri = segment.uri,
                                byteRange = segment.byteRange,
                                outputFile = outputFile,
                                encryptionKey = segment.encryptionKey,
                                sequence = segment.sequence,
                                keyCache = keyCache,
                                onBytesCopied = downloadedBytes::addAndGet
                            )
                            val completed = completedSegments.incrementAndGet()
                            trySend(
                                DownloadProgress.SegmentProgress(
                                    completedSegments = completed,
                                    totalSegments = totalSegments,
                                    downloadedBytes = downloadedBytes.get(),
                                    speedBytesPerSecond = calculateSpeed(downloadedBytes.get(), startedAtMillis),
                                    elapsedMillis = elapsedSince(startedAtMillis)
                                )
                            )
                        }
                    }
                }.forEach { it.await() }
                progressTicker.cancel()
            }

            send(
                DownloadProgress.SegmentProgress(
                    completedSegments = totalSegments,
                    totalSegments = totalSegments,
                    downloadedBytes = downloadedBytes.get(),
                    speedBytesPerSecond = calculateSpeed(downloadedBytes.get(), startedAtMillis),
                    elapsedMillis = elapsedSince(startedAtMillis)
                )
            )

            val muxedSegments = AtomicInteger(0)
            val muxingTicker = launch {
                while (isActive) {
                    delay(PROGRESS_TICK_INTERVAL_MILLIS)
                    trySend(
                        DownloadProgress.Muxing(
                            completedSegments = muxedSegments.get(),
                            totalSegments = totalSegments,
                            downloadedBytes = downloadedBytes.get(),
                            elapsedMillis = elapsedSince(startedAtMillis)
                        )
                    )
                }
            }
            send(
                DownloadProgress.Muxing(
                    completedSegments = 0,
                    totalSegments = totalSegments,
                    downloadedBytes = downloadedBytes.get(),
                    elapsedMillis = elapsedSince(startedAtMillis)
                )
            )
            try {
                try {
                    val onSegmentMuxed: (Int, Int) -> Unit = { completed, total ->
                        muxedSegments.set(completed)
                        trySend(
                            DownloadProgress.Muxing(
                                completedSegments = completed,
                                totalSegments = total,
                                downloadedBytes = downloadedBytes.get(),
                                elapsedMillis = elapsedSince(startedAtMillis)
                            )
                        )
                        Unit
                    }
                    if (initializationFile != null) {
                        muxer.muxFmp4SegmentsToMp4(
                            initializationFile = initializationFile,
                            segmentFiles = segmentFiles,
                            outputFile = tempMp4,
                            onSegmentMuxed = onSegmentMuxed
                        )
                    } else {
                        muxer.muxTsSegmentsToMp4(
                            segmentFiles = segmentFiles,
                            outputFile = tempMp4,
                            onSegmentMuxed = onSegmentMuxed
                        )
                    }
                } catch (error: Exception) {
                    throw DownloadMuxException(
                        message = "MP4 合并失败：${error.message ?: "媒体轨道不兼容"}",
                        cause = error
                    )
                }
            } finally {
                muxingTicker.cancel()
            }
            send(
                DownloadProgress.Muxing(
                    completedSegments = totalSegments,
                    totalSegments = totalSegments,
                    downloadedBytes = downloadedBytes.get(),
                    elapsedMillis = elapsedSince(startedAtMillis)
                )
            )

            send(DownloadProgress.Publishing("正在保存到目标目录"))
            val output = outputWriter.publishMp4(tempMp4, displayName, customDirectoryUri)
            send(
                DownloadProgress.Completed(
                    outputLabel = output.label,
                    outputUri = output.uri,
                    downloadedBytes = tempMp4.length(),
                    elapsedMillis = elapsedSince(startedAtMillis),
                    completedSegments = totalSegments,
                    totalSegments = totalSegments
                )
            )
            completedSuccessfully = true
        } finally {
            cancelTaskRequests(taskId)
            if (completedSuccessfully) {
                // 下载成功后断点缓存已经没有继续价值，及时删除可避免历史任务长期占用数倍空间。
                workDir.deleteRecursively()
            } else {
                tempMp4.delete()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.channels.ProducerScope<DownloadProgress>.downloadDirectMp4(
        taskId: String,
        url: String,
        displayName: String,
        workDir: File,
        tempMp4: File,
        customDirectoryUri: Uri?
    ) {
        val partialFile = File(workDir, "$displayName.part")
        val startedAtMillis = SystemClock.elapsedRealtime()
        val resumedBytes = partialFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L

        send(
            DownloadProgress.Preparing(
                if (resumedBytes > 0L) {
                    "检测到已下载 ${formatDownloadBytes(resumedBytes)}，继续下载 MP4"
                } else {
                    "正在准备 MP4 直链下载"
                }
            )
        )

        val downloadedBytes = AtomicLong(resumedBytes)
        var totalBytes = UNKNOWN_TOTAL_BYTES
        var lastProgressEmitMillis = 0L

        downloadDirectFile(
            taskId = taskId,
            url = url,
            outputFile = partialFile,
            resumeBytes = resumedBytes,
            onResponse = { resolvedTotalBytes, actualResumeBytes ->
                totalBytes = resolvedTotalBytes
                downloadedBytes.set(actualResumeBytes)
                send(
                    DownloadProgress.FileProgress(
                        downloadedBytes = actualResumeBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSecond = calculateSpeed(actualResumeBytes, startedAtMillis),
                        elapsedMillis = elapsedSince(startedAtMillis)
                    )
                )
            },
            onBytesCopied = { bytesCopied ->
                val currentBytes = downloadedBytes.addAndGet(bytesCopied)
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressEmitMillis >= PROGRESS_TICK_INTERVAL_MILLIS) {
                    lastProgressEmitMillis = now
                    send(
                        DownloadProgress.FileProgress(
                            downloadedBytes = currentBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSecond = calculateSpeed(currentBytes, startedAtMillis),
                            elapsedMillis = elapsedSince(startedAtMillis)
                        )
                    )
                }
            }
        )

        val finalBytes = partialFile.length()
        send(
            DownloadProgress.FileProgress(
                downloadedBytes = finalBytes,
                totalBytes = if (totalBytes > 0L) totalBytes else finalBytes,
                speedBytesPerSecond = calculateSpeed(finalBytes, startedAtMillis),
                elapsedMillis = elapsedSince(startedAtMillis)
            )
        )

        if (tempMp4.exists()) tempMp4.delete()
        check(partialFile.renameTo(tempMp4)) { "无法整理 MP4 临时文件" }

        send(DownloadProgress.Publishing("正在保存到目标目录"))
        val output = outputWriter.publishMp4(tempMp4, displayName, customDirectoryUri)
        send(
            DownloadProgress.Completed(
                outputLabel = output.label,
                outputUri = output.uri,
                downloadedBytes = tempMp4.length(),
                elapsedMillis = elapsedSince(startedAtMillis),
                completedSegments = 0,
                totalSegments = 0
            )
        )
    }

    private suspend fun fetchText(taskId: String, url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        executeTracked(taskId, request).use { response ->
            if (!response.isSuccessful) throw DownloadHttpException("m3u8 请求失败", response.code)
            response.body?.string() ?: error("响应为空")
        }
    }

    private suspend fun fetchContentLength(taskId: String, url: String): Long = withContext(Dispatchers.IO) {
        val headRequest = Request.Builder().url(url).head().build()
        val headLength = runCatching {
            executeTracked(taskId, headRequest).use { response ->
                if (response.isSuccessful) response.body?.contentLength() ?: UNKNOWN_TOTAL_BYTES
                else UNKNOWN_TOTAL_BYTES
            }
        }.getOrDefault(UNKNOWN_TOTAL_BYTES)
        if (headLength > 0L) return@withContext headLength

        val rangeRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        runCatching {
            executeTracked(taskId, rangeRequest).use { response ->
                response.header("Content-Range")
                    ?.substringAfter("/", missingDelimiterValue = "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: response.body?.contentLength()?.takeIf { it > 0L }
                    ?: UNKNOWN_TOTAL_BYTES
            }
        }.getOrDefault(UNKNOWN_TOTAL_BYTES)
    }

    private suspend fun estimateHlsBytes(
        taskId: String,
        mediaPlaylist: HlsMediaPlaylist,
        bandwidth: Long?
    ): Long {
        val initializationBytes = mediaPlaylist.initializationSections.fold(0L) { total, section ->
            val sectionBytes = section.byteRange?.length
                ?: fetchContentLength(taskId, section.uri).takeIf { it > 0L }
                ?: 0L
            DownloadStorageRequirementCalculator.saturatingAdd(total, sectionBytes)
        }
        if (mediaPlaylist.segments.all { it.byteRange != null }) {
            // long: BYTERANGE 已经给出精确网络字节数，直接累计可避免对同一个大文件重复按完整长度估算。
            val segmentBytes = mediaPlaylist.segments.fold(0L) { total, segment ->
                DownloadStorageRequirementCalculator.saturatingAdd(total, segment.byteRange?.length ?: 0L)
            }
            return DownloadStorageRequirementCalculator.saturatingAdd(segmentBytes, initializationBytes)
        }
        val durationSeconds = mediaPlaylist.totalDurationSeconds
        if (durationSeconds <= 0.0) return UNKNOWN_TOTAL_BYTES
        if (bandwidth != null && bandwidth > 0L) {
            // 10% 余量覆盖 m3u8 的码率波动和容器开销，预检宁可保守也不能让合并阶段耗尽空间。
            val mediaBytes = (durationSeconds * bandwidth / 8.0 * HLS_ESTIMATE_MARGIN)
                .toLong()
                .coerceAtLeast(1L)
            return DownloadStorageRequirementCalculator.saturatingAdd(mediaBytes, initializationBytes)
        }

        // 直接 media playlist 没有码率信息时，只请求少量分片头部，用样本字节/时长外推整段大小。
        val segments = mediaPlaylist.segments
        val sampleIndices = listOf(0, segments.lastIndex / 2, segments.lastIndex).distinct()
        var sampledBytes = 0L
        var sampledDuration = 0.0
        sampleIndices.forEach { index ->
            val segment = segments.getOrNull(index) ?: return@forEach
            val segmentDuration = segment.durationSeconds?.takeIf { it > 0.0 } ?: return@forEach
            val segmentBytes = segment.byteRange?.length
                ?: fetchContentLength(taskId, segment.uri).takeIf { it > 0L }
                ?: return@forEach
            sampledBytes = DownloadStorageRequirementCalculator.saturatingAdd(sampledBytes, segmentBytes)
            sampledDuration += segmentDuration
        }
        if (sampledBytes <= 0L || sampledDuration <= 0.0) return UNKNOWN_TOTAL_BYTES
        val estimatedMediaBytes = (sampledBytes / sampledDuration * durationSeconds * HLS_ESTIMATE_MARGIN)
            .toLong()
            .coerceAtLeast(1L)
        return DownloadStorageRequirementCalculator.saturatingAdd(estimatedMediaBytes, initializationBytes)
    }

    private fun prepareOutputNameAndStorage(
        requestedDisplayName: String,
        expectedBytes: Long,
        customDirectoryUri: Uri?,
        directMp4: Boolean = true,
        fmp4Hls: Boolean = false
    ): String {
        val resolvedName = outputWriter.resolveUniqueDisplayName(requestedDisplayName, customDirectoryUri)
        if (expectedBytes > 0L) {
            val availableBytes = outputWriter.availableBytes(customDirectoryUri)
            val requiredBytes = DownloadStorageRequirementCalculator.calculate(expectedBytes, directMp4, fmp4Hls)
            if (availableBytes > 0L && availableBytes < requiredBytes) {
                error(
                    "存储空间不足：预计需要 ${formatDownloadBytes(requiredBytes)}，" +
                        "当前剩余 ${formatDownloadBytes(availableBytes)}"
                )
            }
        }
        return resolvedName
    }

    private suspend fun fetchBytes(taskId: String, url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        executeTracked(taskId, request).use { response ->
            if (!response.isSuccessful) throw DownloadHttpException("密钥下载失败", response.code)
            response.body?.bytes() ?: error("密钥响应为空")
        }
    }

    private suspend fun downloadFile(
        taskId: String,
        url: String,
        outputFile: File,
        byteRange: HlsByteRange?,
        onBytesCopied: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val requestBuilder = Request.Builder().url(url)
        if (byteRange != null) {
            requestBuilder.header("Range", "bytes=${byteRange.offset}-${byteRange.endInclusive}")
        }
        val request = requestBuilder.build()
        executeTracked(taskId, request).use { response ->
            if (!response.isSuccessful) throw DownloadHttpException("分片下载失败", response.code)
            val body = response.body ?: error("分片响应为空")
            byteRange?.let { expectedRange ->
                HlsByteRangeResponseValidator.validationError(
                    statusCode = response.code,
                    contentRange = response.header("Content-Range"),
                    contentLength = body.contentLength(),
                    expectedRange = expectedRange
                )?.let { error(it) }
            }
            var bytesCopied = 0L
            body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        onBytesCopied(read.toLong())
                    }
                }
            }
            if (byteRange != null && bytesCopied != byteRange.length) {
                outputFile.delete()
                error("BYTERANGE 实际下载长度不匹配")
            }
            bytesCopied
        }
    }

    private suspend fun downloadHlsResource(
        taskId: String,
        uri: String,
        byteRange: HlsByteRange?,
        outputFile: File,
        encryptionKey: HlsEncryptionKey?,
        sequence: Int,
        keyCache: ConcurrentHashMap<String, ByteArray>,
        onBytesCopied: (Long) -> Unit
    ) {
        val temporaryFile = File(outputFile.parentFile, outputFile.name + if (encryptionKey != null) ".enc" else ".part")
        downloadFile(
            taskId = taskId,
            url = uri,
            outputFile = temporaryFile,
            byteRange = byteRange,
            onBytesCopied = onBytesCopied
        )
        if (encryptionKey != null) {
            val completionMarker = encryptedCompletionMarker(outputFile)
            completionMarker.delete()
            try {
                decryptAes128Segment(
                    inputFile = temporaryFile,
                    outputFile = outputFile,
                    encryptionKey = encryptionKey,
                    sequence = sequence,
                    taskId = taskId,
                    keyCache = keyCache
                )
                check(completionMarker.createNewFile() || completionMarker.isFile) {
                    "无法记录加密分片完成状态"
                }
                temporaryFile.delete()
            } catch (error: Throwable) {
                // long: 没有完成标记的明文绝不能参与恢复，解密或落标失败时立即回收最终文件。
                outputFile.delete()
                completionMarker.delete()
                throw error
            }
        } else if (!temporaryFile.renameTo(outputFile)) {
            error("分片缓存写入失败")
        }
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            outputFile.delete()
            encryptionKey?.let { encryptedCompletionMarker(outputFile).delete() }
            error("分片下载结果为空")
        }
    }

    private fun isReusableHlsResource(
        file: File,
        byteRange: HlsByteRange?,
        encryptionKey: HlsEncryptionKey?
    ): Boolean {
        val completionMarker = if (encryptionKey != null) encryptedCompletionMarker(file) else null
        val reusable = HlsResourceCacheValidator.isReusable(
            exists = file.exists(),
            fileLength = file.length(),
            expectedRange = byteRange,
            encrypted = encryptionKey != null,
            encryptedComplete = completionMarker?.isFile ?: true
        )
        if (!reusable && encryptionKey != null) {
            // long: 旧版本可能遗留非空但未完整解密的分片，没有完成标记时强制重下，不能把它当作断点缓存。
            file.delete()
            completionMarker?.delete()
        }
        return reusable
    }

    private fun encryptedCompletionMarker(file: File): File {
        return File(file.parentFile, "${file.name}.complete")
    }

    private suspend fun downloadDirectFile(
        taskId: String,
        url: String,
        outputFile: File,
        resumeBytes: Long,
        onResponse: suspend (totalBytes: Long, actualResumeBytes: Long) -> Unit,
        onBytesCopied: suspend (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val requestBuilder = Request.Builder().url(url)
        if (resumeBytes > 0L) {
            requestBuilder.header("Range", "bytes=$resumeBytes-")
        }
        executeTracked(taskId, requestBuilder.build()).use { response ->
            if (resumeBytes > 0L && response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                error("服务器拒绝继续下载，请重新下载任务")
            }
            if (!response.isSuccessful) throw DownloadHttpException("MP4 下载失败", response.code)

            val body = response.body ?: error("MP4 响应为空")
            val validatedResumeTotalBytes = if (resumeBytes > 0L && response.code == HTTP_PARTIAL_CONTENT) {
                val contentRange = response.header("Content-Range")
                DirectMp4ResumeResponseValidator.validationError(
                    statusCode = response.code,
                    contentRange = contentRange,
                    contentLength = body.contentLength(),
                    expectedResumeBytes = resumeBytes
                )?.let { validationError ->
                    outputFile.delete()
                    error("$validationError，已清理断点文件，请重新下载任务")
                }
                DirectMp4ResumeResponseValidator.totalBytes(contentRange)
                    ?: error("MP4 断点响应总大小无效")
            } else {
                null
            }
            val actualResumeBytes = if (resumeBytes > 0L && response.code == HTTP_PARTIAL_CONTENT) {
                resumeBytes
            } else {
                if (resumeBytes > 0L) outputFile.delete()
                0L
            }
            val totalBytes = validatedResumeTotalBytes ?: response.resolveDownloadTotalBytes(actualResumeBytes)
            onResponse(totalBytes, actualResumeBytes)

            var bytesCopied = actualResumeBytes
            val appendToPartial = actualResumeBytes > 0L
            // long 2026-06-24 23:50:00: MP4 直链暂停后只能依赖服务端 Range，支持时追加写入；不支持时重新覆盖临时文件，避免拼出损坏视频。
            body.byteStream().use { input ->
                FileOutputStream(outputFile, appendToPartial).use { output ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        onBytesCopied(read.toLong())
                    }
                }
            }
            bytesCopied
        }
    }

    private suspend fun decryptAes128Segment(
        inputFile: File,
        outputFile: File,
        encryptionKey: HlsEncryptionKey,
        sequence: Int,
        taskId: String,
        keyCache: ConcurrentHashMap<String, ByteArray>
    ) = withContext(Dispatchers.IO) {
        val keyBytes = keyCache[encryptionKey.uri] ?: fetchBytes(taskId, encryptionKey.uri).also { fetched ->
            if (fetched.size != AES_128_KEY_BYTES) {
                error("AES-128 密钥长度异常：${fetched.size} bytes")
            }
            keyCache.putIfAbsent(encryptionKey.uri, fetched)
        }
        val cipher = newAesCbcCipher(
            keyBytes = keyBytes,
            ivBytes = encryptionKey.ivHex?.let(::parseIvHex) ?: ivFromSequence(sequence)
        )
        val stagedOutput = File(outputFile.parentFile, "${outputFile.name}.dec.part")
        stagedOutput.delete()
        try {
            inputFile.inputStream().use { rawInput ->
                CipherInputStream(rawInput, cipher).use { input ->
                    stagedOutput.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            coroutineContext.ensureActive()
            check(!outputFile.exists()) { "加密分片目标文件已存在" }
            // long: 临时明文与最终文件位于同一缓存目录，只接受原子移动，避免暂停时暴露半个可复用分片。
            Files.move(stagedOutput.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            stagedOutput.delete()
        }
    }

    private fun executeTracked(taskId: String, request: Request): okhttp3.Response {
        val call = client.newCall(request)
        val calls = activeCallsByTask.getOrPut(taskId) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }
        calls.add(call)
        return try {
            call.execute()
        } finally {
            calls.remove(call)
            if (calls.isEmpty()) {
                activeCallsByTask.remove(taskId, calls)
            }
        }
    }

    private fun newAesCbcCipher(keyBytes: ByteArray, ivBytes: ByteArray): Cipher {
        val cipher = runCatching { Cipher.getInstance("AES/CBC/PKCS7Padding") }
            .getOrElse { Cipher.getInstance("AES/CBC/PKCS5Padding") }
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes)
        )
        return cipher
    }

    private fun parseIvHex(ivHex: String): ByteArray {
        val normalized = ivHex.removePrefix("0x").removePrefix("0X")
        if (normalized.length > AES_BLOCK_BYTES * 2) error("IV 长度异常")
        val padded = normalized.padStart(AES_BLOCK_BYTES * 2, '0')
        return ByteArray(AES_BLOCK_BYTES) { index ->
            padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ivFromSequence(sequence: Int): ByteArray {
        val iv = ByteArray(AES_BLOCK_BYTES)
        var value = sequence.toLong()
        for (index in AES_BLOCK_BYTES - 1 downTo 0) {
            iv[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return iv
    }

    private fun calculateSpeed(downloadedBytes: Long, startedAtMillis: Long): Long {
        val elapsed = elapsedSince(startedAtMillis).coerceAtLeast(1L)
        return downloadedBytes * 1000 / elapsed
    }

    private fun elapsedSince(startedAtMillis: Long): Long {
        return SystemClock.elapsedRealtime() - startedAtMillis
    }

    private fun sanitizeFileName(input: String): String {
        return input.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(80)
    }

    private fun buildMp4DisplayName(fileNameHint: String): String {
        val sanitized = sanitizeFileName(fileNameHint).ifBlank { "liuguang-${System.currentTimeMillis()}" }
        return if (sanitized.endsWith(".mp4", ignoreCase = true)) sanitized else "$sanitized.mp4"
    }

    private fun isDirectMp4Url(url: String): Boolean {
        val normalized = url.trim()
        return (normalized.startsWith("http://") || normalized.startsWith("https://")) &&
            normalized.substringBefore("?").contains(".mp4", ignoreCase = true)
    }

    private fun Response.resolveDownloadTotalBytes(resumeBytes: Long): Long {
        header("Content-Range")
            ?.substringAfter("/", missingDelimiterValue = "")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }
        val contentLength = body?.contentLength() ?: UNKNOWN_TOTAL_BYTES
        return if (contentLength > 0L) resumeBytes + contentLength else UNKNOWN_TOTAL_BYTES
    }

    private fun formatDownloadBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    private companion object {
        private const val DEFAULT_COPY_BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_TICK_INTERVAL_MILLIS = 1_000L
        private const val AES_128_KEY_BYTES = 16
        private const val AES_BLOCK_BYTES = 16
        private const val UNKNOWN_TOTAL_BYTES = -1L
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HLS_ESTIMATE_MARGIN = 1.10
    }
}
