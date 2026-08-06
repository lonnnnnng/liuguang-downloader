# 流光 App 唤起下载器协议

适用版本：v1.0.9

## Deep link 格式

主格式：

```text
liuguangdl://download/add?url=<encoded_download_url>&title=<encoded_file_name>
```

参数：

- `url`：必填，m3u8 或 MP4 在线资源地址，需要 URL encode。
- `title`：可选，建议传视频名、剧集名或 `剧名-第几集`，需要 URL encode。

为兼容已有调用，文件名也可以使用 `name` 或 `fileName` 参数；优先使用 `title`。

示例：

```text
liuguangdl://download/add?url=https%3A%2F%2Fv.gsuus.com%2Fplay%2FbmZqVyAd%2Findex.m3u8&title=%E6%B5%81%E5%85%89%E6%B5%8B%E8%AF%95
```

## Android 调用建议

流光 App 侧建议显式指定包名，避免系统弹选择器：

```kotlin
val deepLink = Uri.Builder()
    .scheme("liuguangdl")
    .authority("download")
    .path("add")
    .appendQueryParameter("url", downloadUrl)
    .appendQueryParameter("title", fileName)
    .build()

val intent = Intent(Intent.ACTION_VIEW, deepLink).apply {
    setPackage("com.liuguang.downloader")
}
startActivity(intent)
```

下载器收到后会打开新建任务弹框，并自动填充下载地址和文件名。

下载器只接受 `http://` 或 `https://` 的 m3u8 / MP4 链接。预填后仍需由用户点击“确定”创建任务，流光 App 不应假设唤起后会自动开始下载。

## 兼容 Intent extra

如果后续不想用 deep link，也可以用显式 Intent extra：

```kotlin
Intent(Intent.ACTION_VIEW).apply {
    setPackage("com.liuguang.downloader")
    putExtra("com.liuguang.downloader.extra.DOWNLOAD_URL", downloadUrl)
    putExtra("com.liuguang.downloader.extra.M3U8_URL", m3u8Url)
    putExtra("com.liuguang.downloader.extra.FILE_NAME", fileName)
}
```

下载器按以下顺序读取链接：deep link 的 `url`、`com.liuguang.downloader.extra.DOWNLOAD_URL`、`com.liuguang.downloader.extra.M3U8_URL`、`Intent.EXTRA_TEXT`、`Intent.dataString`。文件名按 `title` / `name` / `fileName`，随后 `com.liuguang.downloader.extra.FILE_NAME`、`Intent.EXTRA_TITLE` 读取。

也兼容 `ACTION_SEND` + `text/plain` 分享链接。对接流光时仍推荐使用上述显式包名的 deep link，避免其他应用接收或触发系统选择器。
