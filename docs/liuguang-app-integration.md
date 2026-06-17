# 流光 App 唤起下载器协议

## Deep link 格式

主格式：

```text
liuguangdl://download/add?url=<encoded_m3u8_url>&title=<encoded_file_name>
```

参数：

- `url`：必填，m3u8 在线资源地址，需要 URL encode。
- `title`：可选，建议传视频名、剧集名或 `剧名-第几集`，需要 URL encode。

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
    .appendQueryParameter("url", m3u8Url)
    .appendQueryParameter("title", fileName)
    .build()

val intent = Intent(Intent.ACTION_VIEW, deepLink).apply {
    setPackage("com.liuguang.downloader")
}
startActivity(intent)
```

下载器收到后会打开新建任务弹框，并自动填充 m3u8 地址和文件名。

## 兼容 Intent extra

如果后续不想用 deep link，也可以用显式 Intent extra：

```kotlin
Intent(Intent.ACTION_VIEW).apply {
    setPackage("com.liuguang.downloader")
    putExtra("com.liuguang.downloader.extra.M3U8_URL", m3u8Url)
    putExtra("com.liuguang.downloader.extra.FILE_NAME", fileName)
}
```
