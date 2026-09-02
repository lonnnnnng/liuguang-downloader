package com.liuguang.downloader.data.download

internal object DownloadStorageRequirementCalculator {
    fun calculate(expectedBytes: Long, directMp4: Boolean, fmp4Hls: Boolean = false): Long {
        require(expectedBytes >= 0L) { "预计下载大小不能为负数" }
        val copies = when {
            directMp4 -> 2L
            fmp4Hls -> 4L
            else -> 3L
        }
        val copiedBytes = saturatingMultiply(expectedBytes, copies)
        return saturatingAdd(copiedBytes, STORAGE_RESERVE_BYTES)
    }

    fun saturatingAdd(first: Long, second: Long): Long {
        require(first >= 0L && second >= 0L) { "存储空间估算不能使用负数" }
        // long: 远端长度不可信，溢出时按最大空间处理，避免负数绕过“空间不足”保护。
        return if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
    }

    private fun saturatingMultiply(value: Long, multiplier: Long): Long {
        return if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier
    }

    private const val STORAGE_RESERVE_BYTES = 16L * 1024L * 1024L
}
