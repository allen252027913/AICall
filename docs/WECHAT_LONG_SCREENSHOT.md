# 微信当前页面长截图

由本 Android App 发起，对微信内当前可见的小程序或公众号页面向下滚动截图，输出 Android `Bitmap`、私有 PNG 和相册 PNG，并自动请求图片识别接口。无需修改微信页面，也不需要 root 或 ADB 参与实际截图。

## 合图后的联网识别

`VoiceCallService` 在取得成功合成的 Bitmap 后调用 `LongScreenshotUploadEncoder`。上传按完整 Base64 JSON 请求体控制为最多 **900 KiB**（`ProductScreenshotPayload.MAX_REQUEST_BYTES`），这只是保守的客户端预算，并非已确认的网关配置。先保持尺寸尝试 JPEG 质量 90、80、65、50；仍超限则按宽度约 80% 逐级等比例缩小，每级重新尝试质量，保留全部内容。宽度不缩到 256 像素以下（原图更窄则保持宽度），仍不满足预算时停止上传并记录错误，避免无限缩小。上传压缩可能降低文字清晰度，不修改用于本地与相册保存的原 Bitmap。

2026-09-29 的 HTTP 413 日志中，JPEG 为 2,425,628 bytes；按当前无转义 Base64 JSON 编码，请求体为 3,234,190 bytes（约 3.08 MiB）。原先只检查图片不超过 10 MiB，无法保证网关接受。现在发送前还会校验实际请求体预算，HTML 413 也按请求体过大报告，不再误提示登录或 JSON 解析错误。若压缩后仍收到 413，需核对实际网关和应用 JSON 解析器限制；若要支持原契约的 10 MiB 图片，需要容纳约 13.34 MiB 的 Base64 JSON，可考虑将该接口各层上限设为 16 MiB，并在确认生效后同步提高客户端预算。若入口使用 Nginx，相关配置为 [`client_max_body_size`](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_max_body_size)，不能从当前日志确认服务端具体组件和阈值。

随后由独立线程调用 `ProductScreenshotClient`，协议对应 `/Users/allen/Desktop/test-product-screenshot.py`：

- `POST https://pre-tm-app-turbo.alibaba-inc.com/api/offline/product/analyze`
- 请求头：`Content-Type: application/json`、`Accept: application/json`、随机 UUID 的 `X-Request-Id`。
- 请求体：`{"imageBase64":"不带 data URI 前缀的 Base64 图片"}`。
- 连接和读取超时均为 300 秒，响应体最多 1 MiB；不会自动重试 POST 或跟随登录重定向。
- HTTP 200 后校验 `anchor_name`、`live_time_desc`、`product_list`、`knowledge_id`、`knowledge_type`、`knowledge_keyword`。商品字段须与脚本保持一致；`knowledge_*` 按脚本仅校验存在，不额外限制类型。
- 不把模型 API Key 放入 Android；脚本要求的模型配置仍由服务端负责。

识别结果不播报，也不改写 OCR 记录；全部打印到 tag **`zunyu`**：

```sh
adb logcat -s zunyu
```

日志前缀为 `long_screenshot_recognition`，包含请求 ID、原始尺寸 `sourceWidth/sourceHeight`、上传尺寸 `width/height`、JPEG `quality`、`imageBytes`、`requestBytes` 和 `requestBudget`、HTTP 200 的耗时和分段 `result[1]`、`result[2]` 等完整 JSON。不会打印图片 Base64。错误日志会标明 HTTP 错误、结构校验、网络或超时问题；结构化服务端错误也会打印，非 JSON 错误页仅记录摘要。

只要合图成功（包括提前中止后保存的已匹配部分）就请求一次；取消或合图失败不会发起。网络与相册保存独立，网络失败不阻止保存。新识别请求、结束通话、停止共享屏幕或服务销毁会取消旧请求。手机需能访问上述预发域名；本次仅通过假连接验证协议与错误处理，没有向真实服务发送用户截图。

## 开源项目调研

2026-09-29 查询 GitHub 页面展示值（star 会变动，网页可能有缓存）。这是本次候选比较，不是全站排行榜。

| 项目 | Star | 许可证 | 适配判断 |
| --- | ---: | --- | --- |
| [PGSSoft/scrollscreenshot](https://github.com/PGSSoft/scrollscreenshot) | 723 | MIT | 最接近滚动长截图，但属于电脑端 Java/ADB 工具，依赖设备输入编号，不能直接作为当前 App 的移动端 SDK 接入。 |
| [jraska/Falcon](https://github.com/jraska/Falcon) | 413 | Apache-2.0 | Activity 截图，主要服务于本 App 界面和测试；不解决微信跨进程滚动拼接。 |
| [nisrulz/screenshott](https://github.com/nisrulz/screenshott) | 325 | Apache-2.0 | View/Activity 截图，不能获取微信进程里的 View。 |
| [bolteu/screenshotty](https://github.com/bolteu/screenshotty) | 283 | MIT | 封装 PixelCopy、MediaProjection、Canvas 的单帧截图；README 提供 1.0.4，没有完整滚动拼接方案。 |

选择：复用工程已有的 `ScreenCaptureEngine` 和辅助功能手势，独立实现重叠匹配与拼接。没有添加以上项目的依赖，也没有复制其源码。避免为 star 数引入不适合微信跨应用场景的依赖。

Android 14+ 每次新 MediaProjection 会话需要系统授权，不能复用授权启动多次虚拟显示；这里复用当前已经授权的录屏会话。参见 [Android MediaProjection](https://developer.android.com/media/grow/media-projection)。相册通过 MediaStore 发布，自有图片在 Android 10+ 无需申请读取整个相册权限；Android 8/9 需要写存储权限。参见 [Android 共享媒体存储](https://developer.android.com/training/data-storage/shared/media)。

## 使用

1. 开始本地通话，开启辅助功能，授权共享**整个屏幕**。若选择只共享本 App，切到微信后录到的不会是目标页面。
2. 在微信打开目标小程序页面或公众号文章，定位到希望开始的位置，等待图片加载完成。
3. 保持通话，在微信内说“长截图”；也可以回到本 App 点击“微信当前页面长截图”，然后在 10 秒内切回微信目标页面。
4. 采集期间不要手动滚动、切换页面或旋转屏幕。结果保存在系统相册「微信长截图」。私有 PNG 保存在 `filesDir/wechat_long_screenshots/*.png`，不会进入原有 OCR 识别记录。
5. 说“先停一下”、停止屏幕共享或结束通话可以取消；切出微信也会停止。取消的采集不发布新的图片。

判定到底并结束取帧后，会显示 Toast「已滑动到底，正在拼接长截图」；Bitmap 拼接成功后显示「长截图拼接完成」。提示在主线程显示，不会被采集进长图；完成提示表示拼接成功，相册保存结果仍以最终结果信息为准。若滚动或匹配提前中止，会分别提示「上滑未成功，仅拼接了部分内容」或「画面对齐重试失败，仅拼接了部分内容」。

长截图从当前位置持续向下滚动截图，不受“设置 → 微信商品采集次数”影响，也没有 10／50 帧上限。连续三次完成上滑并等待页面稳定后，均未发现移动或新帧时，按到达底部结束；新内容出现会重置确认计数。截图无法对齐时，暂停滑动，在同一位置等待并重新取帧，最多尝试三次。仅重试仍失败或滚动失败时保存已匹配部分，并明确提示具体原因；重试期间没有新帧不能当成到底。底部判定基于屏幕变化，慢加载、长段空白或触摸被遮挡仍可能造成提前判断，并非微信提供的页面底部信号。无限信息流需要说“先停一下”或结束通话取消。

Android 8/9 的按钮入口会先请求写存储权限；直接使用语音且未授权时仍可保存私有 PNG，并提示相册保存失败。授权后可再次截图。

## Android 调用与 Bitmap 所有权

`WeChatLongScreenshotCapture.capture()` 是阻塞的工作线程 API，不能在主线程调用。现有 `VoiceCallService.executeWeChatLongScreenshot()` 已完成集成：等待微信前台 → 隐藏 PiP → 取帧／短距离慢速上滑／等待稳定 → 拼接 → 恢复 PiP → 保存。

```kotlin
// 在工作线程、录屏授权有效、目标页面前台的前提下调用。
val result = WeChatLongScreenshotCapture(context.cacheDir).capture(
  isActive = { sessionStillActiveAndWeChatIsForeground() },
  captureFrame = { screenCapture.captureNext() },
  scroll = {
    screenCapture.discardPendingFrame()
    scrollWeChatAndWaitForGestureCompletion()
  },
  settle = { waitForPageAndCheckCancellation() },
)
try {
  val bitmap: android.graphics.Bitmap = result.bitmap
  val png = LongScreenshotStorage(context).saveBitmap(bitmap)
  val galleryUri = RecordGalleryExporter(context).save(png, "image/png", "微信长截图")
  // 消费 bitmap / png / galleryUri；result.stop 包含停止原因。
} finally {
  result.bitmap.recycle()
}
```

示例中的前台检查、手势等待和页面等待函数由调用方提供，完整实现见 VoiceCallService。`captureFrame` 返回 null 仅表示当前没有新帧，采集错误应抛异常。每个非空 `captureFrame` 返回值的所有权转交采集器；返回的 `result.bitmap` 归调用者所有，用完必须回收。不要通过 Intent/Binder 传递大 Bitmap，跨组件使用文件或 Uri。

`LongScreenshotStitcher` 会把已匹配分片暂存到 cacheDir，最多持有前后两屏；返回前合成一次 Bitmap。采集过程不因长图尺寸而停止；合成时按 `min(12,000,000, maxHeapBytes / 16)` 像素预算及 30,000 像素输出高度上限等比例缩小整张图，保留从起点到末屏的完整范围。长图较长时清晰度会降低，结果会提示已缩小。中间分片占用磁盘会随页面长度增长，磁盘不足会报错。正常结束、失败和取消都会清理临时分片。私有最终 PNG 持久保存，清除 App 数据或卸载会删除它们；已发布的相册图片独立保留。

## 实现边界与验证

- 从当前位置向下采集，不自动回到页面顶部，也不还原滚动位置。
- 匹配的是纵向像素平移。稳定的顶部／底部栏只保留一次；固定边界自动推断最多覆盖每端 1/4 屏。复杂吸顶变化、悬浮按钮、长段空白、重复列表、视频、动画、异步加载可能提前停止或影响接缝，不能保证任意微信页面都无缝。
- 每次用 500 毫秒滚动约 40% 屏高，再等待 900 毫秒后取帧；这不是微信的页面加载完成事件。网络慢时应先等待页面加载。
- `FLAG_SECURE` 等受保护画面遵循系统限制；不实现绕过。
- 横竖屏／尺寸变化会停止匹配。当前工程已有录屏大小处理逻辑未重构，应保持同一方向，并使用整个屏幕共享。
- 相册失败保留私有原图并返回错误，不能把私有保存成功当作相册成功。

验证命令：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.fleey.ppocrv5.LongScreenshotTest
```

JVM 测试覆盖命令识别、录屏撤销生命周期、固定栏识别、真实滚动距离、末段小幅滚动、静止画面、重复纹理和不相关画面。设备测试覆盖逐像素合成、超过十帧持续采集、无新帧的静态底部、取消、超长图整体缩放、原图与相册 PNG 字节一致性。真机还应分别检查微信小程序、公众号文章、动态吸顶、慢网络、取消、Android 8/9 拒绝相册授权以及 Android 14+ 录屏授权。

本次环境验证（2026-09-29）：使用本地 Kotlin 2.3.20 编译器及已有依赖完成全部主程序 Kotlin 源码、全部 JVM 测试源码和新增设备测试源码编译；本版独立 JUnit runner 执行 319 项 JVM 测试通过（含超过 50 帧仍继续采集、到底计数重置和整图缩放、对齐重试、图片识别 HTTP 契约、HTML 413 分类和 Base64 JSON 请求体预算边界回归）。新增大图压缩设备用例仅完成编译，未执行。正常 Gradle 构建因沙箱禁止本地 socket 启动失败，ADB 后续连接也因同一限制无法启动服务，因此尚未完成 APK 打包和设备测试执行。源码编译不能替代 APK／微信真机验收。

诊断日志：`adb logcat -s zunyu` 中的 `long_screenshot alignment` 记录已匹配帧数、当前对齐尝试次数与结果；`long_screenshot stopped` 记录结束原因。日志不包含屏幕图片或页面文字。
