# 流光下载器技术方案

文档状态：v1.0.8

更新日期：2026-07-22

## 技术栈

- Android 原生 Kotlin、Jetpack Compose / Material 3
- Kotlin Coroutines / Flow
- OkHttp
- Android Foreground Service、MediaStore、Storage Access Framework
- Android `MediaExtractor` / `MediaMuxer`
- SharedPreferences + JSON
- GitHub Releases REST API、FileProvider、系统 APK 安装器

## 当前结构

### `MainActivity.kt` 与 `ui/`

- 单 Activity Compose 界面，下载页和设置页为一级页面。
- `DownloaderViewModel` 管理下载草稿、目录设置、并发配置和任务列表状态。
- `UpdateViewModel` 独立管理检查、下载、待安装和失败状态，避免与视频下载任务队列混用。
- 通过 `BackHandler` 在一级页面的系统返回/返回手势上显示退出确认；退出 Activity 不取消前台下载服务。

### `domain/hls/`

- 解析 master playlist、media playlist 和 variant 信息。
- 依据分辨率和 `BANDWIDTH` 选择默认播放流。
- 识别 AES-128 密钥信息，并在 SAMPLE-AES、fMP4、BYTERANGE 等不支持场景给出失败原因。

### `data/download/`

- `DownloadForegroundService` 维护队列、最大并行任务数、暂停、恢复、删除和前台服务生命周期。
- `M3u8DownloadEngine` 用 OkHttp 下载 playlist、分片和 AES-128 密钥；通过协程信号量限制 HLS 分片并发数。
- HLS 工作目录位于 `cacheDir/hls-downloads/<taskId>`。已下载分片可用于暂停后的继续下载；重新下载和删除运行中/等待中任务会清理对应缓存。
- `Mp4Muxer` 是当前具体实现，不存在独立 `data:muxer` 模块或接口。它读取 TS 分片中的音视频轨道，并通过 `MediaExtractor` / `MediaMuxer` 依次重建 MP4 时间线。
- `DownloadOutputWriter` 使用 Android 10+ MediaStore 发布到 `Downloads/liuguang-download`，使用 `DocumentFile` 写入自定义目录；Android 9 及以下使用旧版公共 Downloads 路径。
- `DownloadTaskStore` 使用 SharedPreferences 中的 JSON 保存任务快照，并通过 `StateFlow` 向 UI 发布状态。

## MP4 合并边界

FFmpeg Kit 已停止维护，因此当前实现不绑定 FFmpeg 二进制或其分发链路。`MediaExtractor` / `MediaMuxer` 方案减少了 ABI、许可证和包体积负担，但只适用于能够被 Android 媒体组件识别的常见 MPEG-TS 音视频轨道。

不支持的 HLS 容器、样本加密和轨道变化会在 playlist 解析或合并阶段失败；不会尝试 DRM 绕过或隐式转码。

## 在线更新流程

1. `GitHubUpdateRepository` 请求 `GET /repos/lonnnnnng/liuguang-downloader/releases/latest`，仅使用正式 Release 的 `.apk` 资产。
2. 比较 Release `tag_name` 和当前 `versionName`，发现新版本后向用户展示发布说明。
3. APK 下载只允许 HTTPS 的 GitHub / GitHubusercontent 地址，手动跟随受信任重定向，限制文件大小为 250 MB。
4. 下载完成后验证 GitHub asset 的 SHA-256，读取 APK 的包名、`versionCode` 和签名证书；必须与当前应用包名、较高版本号和当前签名一致。
5. APK 保存在 `cacheDir/update-apk/`，由非导出的 `UpdateFileProvider` 生成 `content://` URI。
6. Android 8.0 及以上先检查 `canRequestPackageInstalls()`；未授权时打开应用级未知来源设置，授权后调用系统安装器。应用不执行静默安装。

## 构建与发布

- 默认版本由 `app/build.gradle.kts` 控制，当前为 `versionName 1.0.8`、`versionCode 108`。
- 本地正式包使用 `local-signing/liuguang-release.env` 提供的签名环境变量构建。
- GitHub Actions 工作流只响应 `workflow_dispatch`；普通 push 和 tag 不会自动发版。
- 发布前至少执行 `testDebugUnitTest`、`lintDebug` 和 `assembleRelease`，并核验 APK 包名、版本号、签名及 GitHub Release 下载回来的 SHA-256。
