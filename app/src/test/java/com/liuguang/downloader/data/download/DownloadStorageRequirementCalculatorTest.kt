package com.liuguang.downloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStorageRequirementCalculatorTest {
    @Test
    fun calculatesExistingCopyAndReserveRules() {
        val reserveBytes = 16L * 1024L * 1024L

        assertEquals(reserveBytes + 2L, DownloadStorageRequirementCalculator.calculate(1L, directMp4 = true))
        assertEquals(reserveBytes + 3L, DownloadStorageRequirementCalculator.calculate(1L, directMp4 = false))
        assertEquals(
            reserveBytes + 4L,
            DownloadStorageRequirementCalculator.calculate(1L, directMp4 = false, fmp4Hls = true)
        )
    }

    @Test
    fun saturatesMultiplicationAndAdditionOverflow() {
        assertEquals(
            Long.MAX_VALUE,
            DownloadStorageRequirementCalculator.calculate(Long.MAX_VALUE / 4L + 1L, false, true)
        )
        assertEquals(
            Long.MAX_VALUE,
            DownloadStorageRequirementCalculator.calculate(Long.MAX_VALUE / 3L + 1L, false)
        )
        assertEquals(
            Long.MAX_VALUE,
            DownloadStorageRequirementCalculator.saturatingAdd(Long.MAX_VALUE - 5L, 6L)
        )
    }
}
