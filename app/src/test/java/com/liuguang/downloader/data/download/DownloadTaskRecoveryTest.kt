package com.liuguang.downloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadTaskRecoveryTest {
    @Test
    fun runningTaskBecomesResumableAfterColdStart() {
        val restored = task(state = DownloadTaskState.Running, isRunning = true)
            .restoredForColdStart()

        assertEquals(DownloadTaskState.Paused, restored.state)
        assertEquals("已中断", restored.status)
        assertEquals("下载进程已中断，点击继续会从已有缓存恢复", restored.detail)
        assertFalse(restored.isRunning)
        assertEquals(0L, restored.speedBytesPerSecond)
    }

    @Test
    fun queuedTaskAlsoBecomesResumableAfterColdStart() {
        val restored = task(state = DownloadTaskState.Queued, isRunning = false)
            .restoredForColdStart()

        assertEquals(DownloadTaskState.Paused, restored.state)
        assertEquals("已中断", restored.status)
        assertFalse(restored.isFailed)
    }

    @Test
    fun completedTaskKeepsCompletionState() {
        val restored = task(state = DownloadTaskState.Completed, isRunning = false)
            .copy(speedBytesPerSecond = 200L)
            .restoredForColdStart()

        assertEquals(DownloadTaskState.Completed, restored.state)
        assertEquals("测试详情", restored.detail)
        assertEquals(0L, restored.speedBytesPerSecond)
    }

    private fun task(state: DownloadTaskState, isRunning: Boolean): DownloadTaskSnapshot {
        return DownloadTaskSnapshot(
            id = "task-1",
            title = "恢复测试",
            url = "https://example.com/video.mp4",
            state = state,
            status = "测试状态",
            progress = 0.5f,
            detail = "测试详情",
            isRunning = isRunning
        )
    }
}
