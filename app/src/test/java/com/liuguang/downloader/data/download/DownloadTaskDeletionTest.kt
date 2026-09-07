package com.liuguang.downloader.data.download

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskDeletionTest {
    @Test
    fun keepsOutputWhenFileDeletionIsNotSelected() {
        val cleared = mutableListOf<String>()
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task("completed", "content://media/external/downloads/10")),
            deleteFiles = false,
            deleteOutput = { error("Output must be preserved") },
            clearCache = { cleared += it }
        )

        assertEquals(setOf("completed"), result.removedTaskIds)
        assertEquals(listOf("completed"), cleared)
        assertNull(result.errorMessage)
    }

    @Test
    fun deletesExactOutputBeforeClearingCache() {
        val uri = "content://provider/tree/folder/document/video%3A17"
        val operations = mutableListOf<String>()
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task("completed", uri)),
            deleteFiles = true,
            deleteOutput = { operations += "file:$it" },
            clearCache = { operations += "cache:$it" }
        )

        assertEquals(listOf("file:$uri", "cache:completed"), operations)
        assertEquals(setOf("completed"), result.removedTaskIds)
        assertNull(result.errorMessage)
    }

    @Test
    fun clearsUnfinishedTasksWithoutTryingToDeleteAnOutput() {
        val tasks = listOf(DownloadTaskState.Queued, DownloadTaskState.Paused, DownloadTaskState.Failed)
            .map { task(it.name, state = it) }
        val cleared = mutableSetOf<String>()
        val result = DownloadTaskDeletion.remove(
            tasks = tasks,
            deleteFiles = true,
            deleteOutput = { error("No output should exist") },
            clearCache = { cleared += it }
        )

        assertEquals(tasks.map { it.id }.toSet(), result.removedTaskIds)
        assertEquals(result.removedTaskIds, cleared)
        assertNull(result.errorMessage)
    }

    @Test
    fun deletesPublishedOutputEvenIfTaskWasPausedBeforeCompletionEvent() {
        val uri = "content://media/external/downloads/11"
        val deleted = mutableListOf<String>()
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task("paused", uri, DownloadTaskState.Paused)),
            deleteFiles = true,
            deleteOutput = { deleted += it },
            clearCache = {}
        )

        assertEquals(listOf(uri), deleted)
        assertEquals(setOf("paused"), result.removedTaskIds)
    }

    @Test
    fun keepsOnlyFailedTasksAndContinuesDeletingOtherTasks() {
        val cleared = mutableListOf<String>()
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task("first", "first-uri"), task("denied", "denied-uri"), task("last", "last-uri")),
            deleteFiles = true,
            deleteOutput = { if (it == "denied-uri") throw SecurityException("Denied") },
            clearCache = { cleared += it }
        )

        assertEquals(setOf("first", "last"), result.removedTaskIds)
        assertEquals(listOf("first", "last"), cleared)
        assertEquals(1, result.failures.size)
        assertTrue(result.errorMessage!!.contains("没有文件删除权限"))
        assertTrue(result.errorMessage!!.contains("记录已保留"))
    }

    @Test
    fun retainsCompletedTaskWhenItsOutputAddressIsMissingOrBlank() {
        listOf(null, "", " ").forEach { uri ->
            val result = DownloadTaskDeletion.remove(
                tasks = listOf(task("completed", uri)),
                deleteFiles = true,
                deleteOutput = { error("Missing output URI") },
                clearCache = { error("Cache must be preserved") }
            )

            assertTrue(result.removedTaskIds.isEmpty())
            assertTrue(result.errorMessage!!.contains("缺少下载文件地址"))
        }
    }

    @Test
    fun retainsUnfinishedTaskWithPublishedLabelButNoOutputAddress() {
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task("paused", state = DownloadTaskState.Paused).copy(outputLabel = "video.mp4")),
            deleteFiles = true,
            deleteOutput = {},
            clearCache = { error("Cache must be preserved") }
        )

        assertTrue(result.removedTaskIds.isEmpty())
        assertTrue(result.errorMessage!!.contains("缺少下载文件地址"))
    }

    @Test
    fun allowsRemovingRecordWithMissingOutputAddressWhenFilesAreKept() {
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task("completed")),
            deleteFiles = false,
            deleteOutput = { error("Output must be preserved") },
            clearCache = {}
        )

        assertEquals(setOf("completed"), result.removedTaskIds)
        assertNull(result.errorMessage)
    }

    @Test
    fun retainsTaskWhenCacheCleanupFails() {
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task("failed-cache", "file-uri")),
            deleteFiles = false,
            deleteOutput = {},
            clearCache = { error("cache unavailable") }
        )

        assertTrue(result.removedTaskIds.isEmpty())
        assertTrue(result.errorMessage!!.contains("cache unavailable"))
    }

    @Test
    fun doesNotSwallowCancellationOrContinueDeleting() {
        val deleted = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            DownloadTaskDeletion.remove(
                tasks = listOf(task("first", "first-uri"), task("second", "second-uri")),
                deleteFiles = true,
                deleteOutput = {
                    deleted += it
                    throw CancellationException("stopped")
                },
                clearCache = { error("Cache must be preserved") }
            )
        }

        assertEquals(listOf("first-uri"), deleted)
    }

    @Test
    fun duplicateTasksAreProcessedOnlyOnce() {
        val task = task("same", "same-uri")
        val operations = mutableListOf<String>()
        val result = DownloadTaskDeletion.remove(
            tasks = listOf(task, task),
            deleteFiles = true,
            deleteOutput = { operations += it },
            clearCache = { operations += it }
        )

        assertEquals(listOf("same-uri", "same"), operations)
        assertEquals(setOf("same"), result.removedTaskIds)
    }

    @Test
    fun emptyTaskListDoesNothing() {
        val result = DownloadTaskDeletion.remove(
            tasks = emptyList(),
            deleteFiles = true,
            deleteOutput = { error("Unexpected output deletion") },
            clearCache = { error("Unexpected cache deletion") }
        )

        assertTrue(result.removedTaskIds.isEmpty())
        assertNull(result.errorMessage)
    }

    @Test
    fun reportsTotalFailuresButLimitsDetailsToThreeTasks() {
        val result = DownloadTaskDeletion.remove(
            tasks = (1..4).map { task("task-$it", "uri-$it") },
            deleteFiles = true,
            deleteOutput = { error("cannot delete") },
            clearCache = {}
        )

        assertEquals(4, result.failures.size)
        assertTrue(result.errorMessage!!.contains("4 个任务未删除"))
        assertTrue(result.errorMessage!!.contains("task-3"))
        assertFalse(result.errorMessage!!.contains("task-4"))
    }

    private fun task(
        id: String,
        outputUri: String? = null,
        state: DownloadTaskState = DownloadTaskState.Completed
    ) = DownloadTaskSnapshot(
        id = id,
        title = id,
        url = "https://example.com/video.mp4",
        state = state,
        status = state.name,
        progress = 0f,
        detail = "",
        outputUri = outputUri
    )
}
