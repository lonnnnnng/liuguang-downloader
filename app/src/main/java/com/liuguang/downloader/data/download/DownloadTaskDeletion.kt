package com.liuguang.downloader.data.download

import kotlinx.coroutines.CancellationException

data class DownloadTaskDeletionState(
    val isDeleting: Boolean = false,
    val errorMessage: String? = null
)

internal data class DownloadTaskDeletionResult(
    val removedTaskIds: Set<String>,
    val failures: List<String>
) {
    val errorMessage: String?
        get() = failures.takeIf { it.isNotEmpty() }?.let {
            "${it.size} 个任务未删除，记录已保留：\n" + it.take(3).joinToString("\n")
        }
}

internal object DownloadTaskDeletion {
    fun remove(
        tasks: List<DownloadTaskSnapshot>,
        deleteFiles: Boolean,
        deleteOutput: (String) -> Unit,
        clearCache: (String) -> Unit
    ): DownloadTaskDeletionResult {
        val removedIds = mutableSetOf<String>()
        val failures = mutableListOf<String>()
        tasks.distinctBy { it.id }.forEach { task ->
            try {
                // long: 文件删除成功后才能移除记录；权限失效时仍保留文件地址，便于重新授权后重试。
                if (deleteFiles) {
                    val outputUri = task.outputUri?.takeIf { it.isNotBlank() }
                    check(outputUri != null || (task.state != DownloadTaskState.Completed && task.outputLabel == null)) {
                        "缺少下载文件地址，无法删除文件；可取消勾选以仅删除任务"
                    }
                    outputUri?.let(deleteOutput)
                }
                clearCache(task.id)
                removedIds += task.id
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val reason = if (error is SecurityException) {
                    "没有文件删除权限，请重新授权保存目录后重试"
                } else {
                    error.message ?: "文件或缓存删除失败"
                }
                failures += "${task.title.take(60)}：$reason"
            }
        }
        return DownloadTaskDeletionResult(removedIds, failures)
    }
}
