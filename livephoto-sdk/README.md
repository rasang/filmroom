# Filmroom LivePhoto Android SDK · 0.1.0-alpha01

独立实验 SDK，位于 `livephoto-sdk` 分支；没有合入 Filmroom 主分支，也不是 Filmroom 正式版。

## 下载与测试

构建工作流：仓库 Actions → **LivePhoto SDK test build**。成功构建后，`downloads/` 下提供独立测试 APK、SDK 源码 ZIP、AAR、核心 JAR 与九组生成样例 ZIP；失败时以 Actions 日志为准，不把未生成的文件称为完成。

测试 App 包名 `dev.filmroom.livephoto.demo`，显示名「LivePhoto SDK 验收」，Android 10+，不覆盖 Filmroom。使用 debug 签名，不是正式发布版本；不同构建签名可能不同，覆盖安装失败时需先卸载旧测试 App。

首次测试：选择一段普通 SDR 视频，选目标品牌格式，勾选「允许实验性厂商格式」，默认截取 3 秒、首帧封面，再点「转换并保存到相册」。请保持 App 前台。随后在系统相册检查实况标识、播放、声音、方向、封面及重开相册后的行为。

vivo 单文件、2104 双文件、2200/2016 双文件要分别测试；不按型号自动承诺兼容。双文件显示成一张图和一个独立视频不算配对成功。荣耀旧尾标是候选配置，不是官方 MediaData Kit。

## 模块

- `core`：纯 Kotlin/JVM 封装、受限生成配置校验、配对与发布事务日志。
- `android`：Media3 视频裁剪/转码、封面抽取、MediaStore 入库与失败回滚。
- `demo`：框架 Activity 测试界面；选视频、选格式、转换、取消、打开相册。

实现：Google、Xiaomi compatibility、Oplus、vivo single、vivo pair 2104、vivo pair legacy、Honor legacy、Samsung SEF 106/107，共九种显式配置。所有厂商配置是实验性写入配置，不是已验收机型列表。

范围：本地 SDR 视频 → H.264/AAC → JPEG 动态照片。首版不支持 HEIC/HDR 输出，不包含厂商授权 SDK。视频有声时可保留音轨；转换与封装均不上传媒体。SDK 校验不能代替真机相册兼容测试。

## 接入

在协程中调用；进度回调可能来自不同线程：

```kotlin
val sdk = LivePhotoSdk(applicationContext)
val result = sdk.convert(VideoToLiveRequest(
    input = videoUri,
    profile = LivePhotoProfile.OPLUS,
    startMs = 0,
    durationMs = 3_000,
    coverOffsetMs = 0,
    keepAudio = true,
    allowExperimentalProfiles = true,
    saveToGallery = true
))
val imageUri = result.publishedUris.first()
```

可分别调用 `create()` 和 `publish()`。`deleteLocalAsset()` 仅删除 SDK 私有输出，不删除用户输入或已经发布的相册文件。`recoverInterruptedPublications()` 按日志回滚未完成发布；双文件并不是跨文件原子事务。

把 `core`、`android` 作为源码模块引入宿主，或正常使用 Maven 依赖管理。单独 AAR 不内嵌 core、Media3、Kotlin/协程依赖；单独核心 JAR 不包含视频转码功能。参考 `android/build.gradle.kts` 的完整依赖声明。

## 构建

需要 JDK 17、Gradle 8.11.1、Android SDK 36、Build Tools 35.0.0。打开此目录（不是 Filmroom 仓库根目录）即可作为独立 Android Studio 项目。

```sh
cd livephoto-sdk
python3 tools/generate_fixtures.py  # 需 ffmpeg、ffprobe、Pillow；只生成合成测试素材
# 无需 Android 设备即可运行核心检查与构建安装包
gradle :livephoto-core:check :livephoto-android:assembleRelease :demo:assembleDebug
python3 tools/verify_outputs.py core/build/selftest core/src/test/resources/fixtures
# 设备测试需连接 Android 真机或模拟器；上述命令不会执行设备测试
# gradle :livephoto-android:connectedDebugAndroidTest
```

原附件中的 Kotlin 源码迁移至此；合成测试输入在构建时重新生成，不包含用户媒体或原厂相机样本。`downloads/build-info.json` 记录构建提交、测试结果及产物摘要。构建成功、Android 测试成功、厂商相册验收分别记录，不能互相替代。

协议参考与归属见 `docs/SOURCES.md`；新代码 MIT。没有上传生产签名密钥，也不使用 Filmroom 的正式签名。
