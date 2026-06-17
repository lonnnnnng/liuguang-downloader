# 流光下载器技术方案

日期：2026-06-15

## 技术栈

- Android 原生 Kotlin
- Jetpack Compose
- OkHttp
- Kotlin Coroutines
- Foreground Service
- Android Storage Access Framework

## 模块划分

### app

Compose UI、导航、权限、下载任务展示、目录选择。

### domain:hls

负责 HLS playlist 解析：

- master playlist 解析
- media playlist 解析
- variant 排序和默认清晰度选择
- AES-128 加密信息解析与不支持加密方式检测

### data:download

负责分片下载：

- 前台下载服务
- 任务状态流
- 队列调度与最大并行任务控制
- 分片并发下载
- AES-128 分片解密
- 取消运行中和等待中的任务

### data:muxer

负责 MP4 输出：

- 暴露 `Mp4Muxer` 接口
- 下载层只依赖接口，不绑定具体实现
- 第一版实现可替换，避免被单一 FFmpeg 分发方式锁死

## MP4 合并策略

已验证：FFmpeg Kit 官方文档标记项目 retired，且说明不会再发布新版本，预编译包也有移除计划。因此第一版不把业务逻辑强绑定到 FFmpeg Kit。

建议抽象：

```kotlin
interface Mp4Muxer {
    suspend fun mux(input: HlsDownloadArtifact, output: OutputTarget): MuxResult
}
```

可选实现：

- 自带可维护的 FFmpeg binary，并清晰处理授权和 ABI 包体积。
- 使用仍维护的 FFmpeg Kit fork，确认来源、授权和 Android 版本兼容性。
- 对常见 MPEG-TS HLS 做自研 remux，后续再补 fMP4。

## 存储策略

默认输出：

`Downloads/liuguang-download/<任务名>.mp4`

用户自定义目录：

- 使用 `ACTION_OPEN_DOCUMENT_TREE`。
- 保存 URI persistable permission。
- 输出文件通过 `DocumentFile` 创建。

临时文件：

- 使用 App 私有目录保存分片和任务缓存。
- 合并成功后清理临时分片。
- 取消任务时由用户选择是否删除临时数据。

## 后台下载

- 下载任务由 Foreground Service 执行。
- 通知栏显示当前任务名、进度和取消动作。
- Android 13+ 请求 `POST_NOTIFICATIONS`。
- Android 14+ 使用合适的 foreground service type，例如 `dataSync`。

## 第一阶段开发顺序

1. Android/Compose 工程骨架。
2. 剪贴板 m3u8 自动识别。
3. HLS master playlist 解析和默认清晰度选择。
4. 下载任务状态流。
5. media playlist 解析与分片下载。
6. 前台服务和通知栏进度。
7. MP4 合并模块。
8. SAF 自定义目录。

Room 持久化、暂停/继续/重试、手动清晰度选择和自定义请求头放到后续版本。
