package com.liuguang.downloader.data.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.liuguang.downloader.domain.hls.HlsEncryptionKey
import com.liuguang.downloader.domain.hls.HlsMasterPlaylistParser
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
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
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

    fun download(
        taskId: String,
        url: String,
        fileNameHint: String,
        customDirectoryUri: Uri?,
        downloadThreadCount: Int
    ): Flow<DownloadProgress> = channelFlow {
        val displayName = sanitizeFileName(fileNameHint).ifBlank { "liuguang-${System.currentTimeMillis()}" } + ".mp4"
        val workDir = workDirectoryForTask(taskId)
        val segmentDir = File(workDir, "segments")
        val tempMp4 = File(workDir, displayName)
        segmentDir.mkdirs()

        try {
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

            val totalSegments = mediaPlaylist.segments.size
            val startedAtMillis = SystemClock.elapsedRealtime()
            val segmentFiles = List(totalSegments) { index ->
                File(segmentDir, index.toString().padStart(5, '0') + ".ts")
            }
            val existingSegments = segmentFiles.map(File::exists)
            val resumedBytes = segmentFiles
                .asSequence()
                .filter(File::exists)
                .sumOf(File::length)
            val initialCompletedSegments = existingSegments.count { it }
            val downloadedBytes = AtomicLong(resumedBytes)
            val completedSegments = AtomicInteger(initialCompletedSegments)
            val keyCache = ConcurrentHashMap<String, ByteArray>()
            val semaphore = Semaphore(downloadThreadCount.coerceAtLeast(1))

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
                            val encryptedFile = if (segment.encryptionKey != null) {
                                File(segmentDir, index.toString().padStart(5, '0') + ".enc")
                            } else {
                                File(segmentDir, index.toString().padStart(5, '0') + ".part")
                            }
                            downloadFile(
                                taskId = taskId,
                                url = segment.uri,
                                outputFile = encryptedFile,
                                onBytesCopied = { bytesCopied ->
                                    downloadedBytes.addAndGet(bytesCopied)
                                }
                            )
                            segment.encryptionKey?.let { encryptionKey ->
                                decryptAes128Segment(
                                    inputFile = encryptedFile,
                                    outputFile = outputFile,
                                    encryptionKey = encryptionKey,
                                    sequence = segment.sequence,
                                    taskId = taskId,
                                    keyCache = keyCache
                                )
                                encryptedFile.delete()
                            } ?: run {
                                encryptedFile.renameTo(outputFile)
                            }
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
                muxer.muxTsSegmentsToMp4(
                    segmentFiles = segmentFiles,
                    outputFile = tempMp4,
                    onSegmentMuxed = { completed, total ->
                        muxedSegments.set(completed)
                        trySend(
                            DownloadProgress.Muxing(
                                completedSegments = completed,
                                totalSegments = total,
                                downloadedBytes = downloadedBytes.get(),
                                elapsedMillis = elapsedSince(startedAtMillis)
                            )
                        )
                    }
                )
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
        } finally {
            cancelTaskRequests(taskId)
            tempMp4.delete()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchText(taskId: String, url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        executeTracked(taskId, request).use { response ->
            if (!response.isSuccessful) error("请求失败：HTTP ${response.code}")
            response.body?.string() ?: error("响应为空")
        }
    }

    private suspend fun fetchBytes(taskId: String, url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        executeTracked(taskId, request).use { response ->
            if (!response.isSuccessful) error("密钥下载失败：HTTP ${response.code}")
            response.body?.bytes() ?: error("密钥响应为空")
        }
    }

    private suspend fun downloadFile(
        taskId: String,
        url: String,
        outputFile: File,
        onBytesCopied: (Long) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        executeTracked(taskId, request).use { response ->
            if (!response.isSuccessful) error("分片下载失败：HTTP ${response.code}")
            val body = response.body ?: error("分片响应为空")
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
        inputFile.inputStream().use { rawInput ->
            CipherInputStream(rawInput, cipher).use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_COPY_BUFFER_SIZE)
                }
            }
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

    private companion object {
        private const val DEFAULT_COPY_BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_TICK_INTERVAL_MILLIS = 1_000L
        private const val AES_128_KEY_BYTES = 16
        private const val AES_BLOCK_BYTES = 16
    }
}
