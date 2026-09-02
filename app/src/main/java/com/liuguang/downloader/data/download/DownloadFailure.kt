package com.liuguang.downloader.data.download

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class DownloadFailureCategory(val displayName: String) {
    Network("网络连接"),
    AccessDenied("访问受限"),
    LinkExpired("链接失效"),
    Storage("存储空间"),
    Unsupported("格式不支持"),
    Muxing("MP4 合并"),
    Server("服务器异常"),
    Unknown("未知错误")
}

data class DownloadFailureInfo(
    val category: DownloadFailureCategory,
    val message: String,
    val retryable: Boolean
)

class DownloadHttpException(
    val operation: String,
    val statusCode: Int
) : IOException("$operation：HTTP $statusCode")

class DownloadMuxException(message: String, cause: Throwable) : IOException(message, cause)

object DownloadFailureClassifier {
    fun classify(error: Throwable): DownloadFailureInfo {
        if (error is DownloadHttpException) return classifyHttp(error)
        if (error is DownloadMuxException) {
            return DownloadFailureInfo(
                DownloadFailureCategory.Muxing,
                error.message ?: "MP4 合并失败",
                false
            )
        }
        if (
            error is UnknownHostException || error is SocketTimeoutException ||
            error is ConnectException || error is SocketException || error is IOException
        ) {
            return DownloadFailureInfo(
                category = DownloadFailureCategory.Network,
                message = "网络连接中断，请检查网络后重试",
                retryable = true
            )
        }

        val message = error.message.orEmpty()
        return when {
            message.contains("存储空间不足") || message.contains("目录") || message.contains("写入") -> {
                DownloadFailureInfo(DownloadFailureCategory.Storage, message.ifBlank { "存储空间或目录不可用" }, false)
            }
            message.contains("暂不支持") || message.contains("加密方式") || message.contains("BYTERANGE") ||
                message.contains("没有解析到可下载分片") -> {
                DownloadFailureInfo(DownloadFailureCategory.Unsupported, message.ifBlank { "资源格式暂不支持" }, false)
            }
            message.contains("音视频轨道") || message.contains("合并") -> {
                DownloadFailureInfo(DownloadFailureCategory.Muxing, message.ifBlank { "MP4 合并失败" }, false)
            }
            message.contains("服务器拒绝继续下载") -> {
                DownloadFailureInfo(DownloadFailureCategory.LinkExpired, message, false)
            }
            else -> DownloadFailureInfo(
                category = DownloadFailureCategory.Unknown,
                message = message.ifBlank { "下载失败，请重试" },
                retryable = false
            )
        }
    }

    private fun classifyHttp(error: DownloadHttpException): DownloadFailureInfo {
        val code = error.statusCode
        return when {
            code == 401 || code == 403 -> DownloadFailureInfo(
                DownloadFailureCategory.AccessDenied,
                "资源访问被拒绝（HTTP $code），链接可能已过期",
                false
            )
            code == 404 || code == 410 -> DownloadFailureInfo(
                DownloadFailureCategory.LinkExpired,
                "资源不存在或链接已失效（HTTP $code）",
                false
            )
            code == 408 || code == 429 || code in 500..599 -> DownloadFailureInfo(
                DownloadFailureCategory.Server,
                "服务器暂时不可用（HTTP $code）",
                true
            )
            else -> DownloadFailureInfo(
                DownloadFailureCategory.Server,
                "${error.operation}（HTTP $code）",
                false
            )
        }
    }
}
