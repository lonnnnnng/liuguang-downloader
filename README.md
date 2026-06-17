# 流光下载器

流光下载器是一个 Android 原生 Kotlin/Compose 应用，用于接收 m3u8 在线资源链接，将 HLS 分片下载、必要时解密，并合并输出为单个 MP4 文件。

它主要作为「流光」App 的下载伴侣使用：流光负责发现、解析和播放资源；流光下载器负责在用户确认后接收下载地址并保存到本地。

> 本项目只面向用户自有或已获授权下载的资源。不内置资源站，不提供 DRM/FairPlay/Widevine 绕过能力，也不规避访问控制。

## 功能

- 自动识别剪贴板中的 m3u8 链接。
- 支持从流光 App 通过 deep link 唤起并填充下载地址和文件名。
- 新建下载任务弹框支持手动编辑 m3u8 地址和文件名。
- 支持下载队列、暂停、继续、删除、重新下载、复制下载链接、打开已下载文件。
- 支持任务历史持久化，App 重启后保留历史下载记录。
- 支持按状态筛选任务：全部、队列中、下载中、已完成、失败。
- 支持设置最大并行任务数和分片下载线程数。
- 默认保存到系统 `Downloads/liuguang-download` 目录。
- 支持在 App 内通过 Android Storage Access Framework 自定义保存目录。
- 支持 master playlist 自动选择清晰度：优先最高分辨率，其次最高带宽。
- 支持常见 MPEG-TS HLS 分片下载和 MP4 合并。
- 支持常见 `#EXT-X-KEY:METHOD=AES-128` 的 TS-HLS 加密流。
- 遇到 DRM、`SAMPLE-AES`、fMP4、BYTERANGE 等暂不支持格式时会提示失败原因。

## 技术栈

- Android 原生 Kotlin
- Jetpack Compose / Material 3
- Kotlin Coroutines / Flow
- OkHttp
- Foreground Service
- Android Storage Access Framework
- `MediaExtractor` / `MediaMuxer` MP4 合并
- SharedPreferences + JSON 任务持久化
- Gradle Kotlin DSL
- GitHub Actions 手动发版流水线

## 系统要求

- Android 8.0+，`minSdk 26`
- `targetSdk 35`
- JDK 17
- Android Gradle Plugin 8.5.2
- Gradle Wrapper 已随仓库提交

## 使用方式

### 下载安装

从 GitHub Releases 下载最新 APK：

- [Releases](https://github.com/lonnnnnng/liuguang-downloader/releases)

安装后打开「流光下载器」，可以通过剪贴板、手动输入或流光 App 唤起创建下载任务。

### App 内创建任务

1. 复制一个 m3u8 链接，或在首页点击右下角 `+`。
2. 在弹框中填入 m3u8 地址。
3. 可选：填写输出文件名。
4. 点击「确定」加入下载队列。
5. 下载完成后，在任务详情中点击「打开」播放本地 MP4。

### 保存目录

默认输出目录：

```text
Downloads/liuguang-download
```

也可以在「设置」页中选择自定义保存目录。自定义目录使用 Android SAF 授权，授权后会持久保存。

## 流光 App 对接

推荐使用专用 deep link：

```text
liuguangdl://download/add?url=<encoded_m3u8_url>&title=<encoded_file_name>
```

参数说明：

- `url`：必填，m3u8 在线资源地址，需要 URL encode。
- `title`：可选，建议传视频名、剧集名或 `剧名-第几集`，需要 URL encode。

示例：

```text
liuguangdl://download/add?url=https%3A%2F%2Fv.gsuus.com%2Fplay%2FbmZqVyAd%2Findex.m3u8&title=%E6%B5%81%E5%85%89%E6%B5%8B%E8%AF%95
```

流光 App 侧 Kotlin 示例：

```kotlin
val deepLink = Uri.Builder()
    .scheme("liuguangdl")
    .authority("download")
    .path("add")
    .appendQueryParameter("url", m3u8Url)
    .appendQueryParameter("title", fileName)
    .build()

val intent = Intent(Intent.ACTION_VIEW, deepLink).apply {
    setPackage("com.liuguang.downloader")
}

startActivity(intent)
```

下载器收到后会打开新建任务弹框，并自动填充 m3u8 地址和文件名。用户点击「确定」后才会开始下载。

兼容 Intent extra：

```kotlin
Intent(Intent.ACTION_VIEW).apply {
    setPackage("com.liuguang.downloader")
    putExtra("com.liuguang.downloader.extra.M3U8_URL", m3u8Url)
    putExtra("com.liuguang.downloader.extra.FILE_NAME", fileName)
}
```

更完整的对接说明见：[docs/liuguang-app-integration.md](docs/liuguang-app-integration.md)。

## 本地开发

克隆仓库：

```bash
git clone https://github.com/lonnnnnng/liuguang-downloader.git
cd liuguang-downloader
```

运行单元测试并构建 debug 包：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 正式发版

仓库内置 GitHub Actions 发布流水线：

```text
.github/workflows/release.yml
```

发布流水线只支持手动触发：

```yaml
on:
  workflow_dispatch:
```

普通 push 不会触发发版。手动发版时需要输入：

- `version_name`：例如 `1.0.1`
- `version_code`：Android `versionCode`
- `prerelease`：是否标记为预发布

正式签名需要配置以下 GitHub Actions Secrets：

- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

## 目录结构

```text
app/src/main/java/com/liuguang/downloader/
├── MainActivity.kt                         # Compose UI 和入口
├── data/download/                          # 下载服务、任务存储、分片下载、MP4 合并
├── domain/hls/                             # HLS playlist 解析
└── ui/                                     # ViewModel 和主题

docs/
├── liuguang-app-integration.md             # 流光 App 对接协议
├── requirements.md                         # 需求说明
└── technical-plan.md                       # 技术方案
```

## 当前限制

- 暂不支持 DRM、Widevine、FairPlay、SAMPLE-AES。
- 暂不支持 fMP4、BYTERANGE 类型 HLS。
- 暂不支持为单个任务配置自定义请求头，如 `Referer`、`Cookie`、`User-Agent`。
- MP4 合并以常见 MPEG-TS HLS 为目标场景。

## License

暂未指定开源许可证。公开仓库可以查看源码，但在明确 License 前请不要默认视为可自由复制、修改或再分发。
