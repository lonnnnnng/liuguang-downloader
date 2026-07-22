package com.liuguang.downloader.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuguang.downloader.data.update.GitHubUpdateRepository
import com.liuguang.downloader.data.update.UpdateStatus
import com.liuguang.downloader.data.update.UpdateUiState
import com.liuguang.downloader.data.update.VersionComparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GitHubUpdateRepository(application)
    private val _uiState = MutableStateFlow(UpdateUiState(currentVersionName = currentVersionName(application)))
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    init {
        checkForUpdates(showUpToDateResult = false)
    }

    fun checkForUpdates(showUpToDateResult: Boolean = true) {
        if (_uiState.value.status == UpdateStatus.Checking ||
            _uiState.value.status == UpdateStatus.Downloading
        ) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                status = UpdateStatus.Checking,
                message = null,
                downloadedFile = null,
                downloadProgress = 0f
            )
            runCatching { repository.getLatestRelease() }
                .onSuccess { release ->
                    val isNewer = VersionComparator.isNewer(
                        candidate = release.versionName,
                        current = _uiState.value.currentVersionName
                    )
                    _uiState.value = _uiState.value.copy(
                        status = when {
                            isNewer -> UpdateStatus.Available
                            showUpToDateResult -> UpdateStatus.UpToDate
                            else -> UpdateStatus.Idle
                        },
                        release = release,
                        message = if (!isNewer && showUpToDateResult) "当前已是最新版本" else null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        status = if (showUpToDateResult) UpdateStatus.Error else UpdateStatus.Idle,
                        message = if (showUpToDateResult) error.userMessage("检查更新失败") else null
                    )
                }
        }
    }

    fun downloadUpdate() {
        val release = _uiState.value.release ?: return
        if (_uiState.value.status == UpdateStatus.Downloading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                status = UpdateStatus.Downloading,
                downloadProgress = 0f,
                message = null
            )
            runCatching {
                repository.downloadAndVerify(release) { progress ->
                    _uiState.value = _uiState.value.copy(downloadProgress = progress)
                }
            }.onSuccess { file ->
                _uiState.value = _uiState.value.copy(
                    status = UpdateStatus.ReadyToInstall,
                    downloadedFile = file,
                    downloadProgress = 1f,
                    message = "安装包已通过安全校验",
                    installationPromptId = System.nanoTime()
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    status = UpdateStatus.Error,
                    downloadedFile = null,
                    message = error.userMessage("下载更新失败")
                )
            }
        }
    }

    fun clearMessage() {
        if (_uiState.value.status == UpdateStatus.UpToDate || _uiState.value.status == UpdateStatus.Error) {
            _uiState.value = _uiState.value.copy(status = UpdateStatus.Idle, message = null)
        }
    }

    private fun Throwable.userMessage(fallback: String): String {
        return message?.takeIf(String::isNotBlank) ?: fallback
    }

    @Suppress("DEPRECATION")
    private fun currentVersionName(application: Application): String {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                application.packageManager.getPackageInfo(
                    application.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                application.packageManager.getPackageInfo(application.packageName, 0)
            }
            info.versionName ?: "未知"
        } catch (_: PackageManager.NameNotFoundException) {
            "未知"
        }
    }
}
