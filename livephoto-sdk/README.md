# Filmroom LivePhoto Android SDK · 0.1.0-alpha02

独立实验 SDK，位于 `livephoto-sdk` 动态图片分支；不合入 Filmroom 主分支，不发布正式版。

## 本版新增

默认按目标品牌自动选择格式，优先使用此前确认成功的配置。返回 SUPPORTED / UNSUPPORTED / UNKNOWN，并持久化用户对具体相册、具体格式的验收结果。vivo 单文件与两种双文件分别记录；三星 107 / 106 分开，不按型号阈值推断支持。

品牌只是初始候选；生成、保存、打开相册都不自动标记成功。转换异常、权限不足与版本查询失败不写入“不支持”。相册/ROM/writer/导出参数变化后不复用旧记录。完整规则、API 与官方 SDK 可选适配方式见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 下载和测试

成功构建后产物放在仓库根目录 [downloads/livephoto-sdk](../downloads/livephoto-sdk/)。构建失败以 Actions 日志为准。工作流 **LivePhoto SDK test build** 运行核心测试、自动选择回归、独立媒体检查、Android 构建与 lint、APK 签名检查。

独立测试 App 包名 `dev.filmroom.livephoto.demo`，Android 10+，不会覆盖 Filmroom。alpha02 为新的 debug 签名构建，无法覆盖旧验收 App 时先卸载旧验收 App，不要卸载 Filmroom。

选择 SDR 视频 → 本机/接收端品牌 → 格式保持“自动” → 勾选实验性格式 → 转换保存。然后打开目标相册检查实况标识、播放、声音、封面和重开行为，返回测试 App 明确记录结果。相册无法自动确定时，选择具体相册后重新生成测试，不能把任意图片查看器当作支持实况。

不识别某配置时可记录失败，下次用户发起转换才尝试下一候选；不会自动生成一堆重复文件。高级手动选择仍保留。跨机生成不使用本机的相册验证记录。

## 模块和范围

- `core`：Kotlin/JVM 封装、受限配置校验、发布日志、格式选择与兼容记录。
- `android`：Media3 裁剪/转码、PTS 封面、MediaStore 发布、相册发现与验收反馈。
- `demo`：独立验收 App。

九种封装：GOOGLE、XIAOMI_COMPAT、OPLUS、VIVO_SINGLE、VIVO_PAIR_2104、VIVO_PAIR_LEGACY、HONOR_LEGACY、SAMSUNG_SEF_107、SAMSUNG_SEF_106。均为具体写入配置，不是五家全机型认证。

本版范围仍是本地 SDR 视频 → H.264/AAC → JPEG 动态照片。没有增加 HDR/HEIC，没有捆绑厂商授权 SDK，不联网、不上传照片或兼容记录。小米等官方能力可由宿主通过 DeviceSupportProbe 注入真实 SDK 调用，其结果仅作能力线索，不冒充自写封装兼容认证。

## 接入

```kotlin
val sdk = LivePhotoSdk(applicationContext)
val request = VideoToLiveRequest(
    input = videoUri,
    // profile = null 是自动；旧的显式 profile 调用仍可用。
    allowExperimentalProfiles = true
)
val selection = sdk.resolveProfile(request)
val result = sdk.convert(request)
// 用户在目标相册真正验收后才调用：
// sdk.compatibility.recordGalleryResult(result, SupportStatus.SUPPORTED)
```

`create()` / `publish()` 可分开调用；`deleteLocalAsset()` 只删除 SDK 私有输出。`recoverInterruptedPublications()` 回滚未完成发布，双文件不具有跨文件原子可见性。进度回调可能来自不同线程，宿主自行切换 UI 线程。

源码模块接入优先；单独 AAR 不包含 core、协程和 Media3 的全部传递依赖，参考 android/build.gradle.kts。单独核心 JAR 不负责视频转码。

## 构建

JDK 17、Gradle 8.11.1、Android SDK 36、Build Tools 35.0.0；Kotlin 2.1.21、AGP 8.10.1、Media3 1.8.0。

```sh
cd livephoto-sdk
python3 tools/generate_fixtures.py
# check 同时包含原有核心检查和新的 compatibilitySelfTest
gradle :livephoto-core:check :livephoto-android:assembleRelease :demo:assembleDebug
python3 tools/verify_outputs.py core/build/selftest core/src/test/resources/fixtures
# 需要连接设备才可执行，CI 只编译 Android 测试：
# gradle :livephoto-android:connectedDebugAndroidTest
```

测试夹具为合成图案与声音，非手机原片。CI 成功不等于各厂商相册已验收。源码与协议研究来源见 [docs/SOURCES.md](docs/SOURCES.md)，新代码 MIT，不使用 Filmroom 生产签名。
