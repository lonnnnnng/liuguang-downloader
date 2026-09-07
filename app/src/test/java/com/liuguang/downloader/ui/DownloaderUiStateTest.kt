package com.liuguang.downloader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloaderUiStateTest {
    @Test
    fun defaultsToThreeParallelTasksAndSixteenSegmentThreads() {
        val state = DownloaderUiState()

        assertEquals(3, state.maxParallelTasks)
        assertEquals(16, state.downloadThreadCount)
    }
}
