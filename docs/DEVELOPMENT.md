# Filmroom 开发指南

[简体中文 README](../README.md) · [English README](../README.en.md) · [历史记录](HISTORY.md)

本指南对应 0.1.16 源码。应用离线运行不等于构建完全离线；首次依赖解析可能需要网络。以下为构建步骤，不表示本次文档更新重新运行了这些测试。

## 环境

- 完整 JDK 17，Android SDK 35，Build Tools 35.0.0。
- 仓库自带 Gradle Wrapper 8.11.1。
- 重建原生组件：Rust/Cargo 1.88.0、Android NDK r27c（27.2.12479018），以及 `aarch64-linux-android` / `x86_64-linux-android` 目标。
- 最低 Android 10 / API 29；默认 ABI 为 ARM64 与 x86_64。

自行创建 `local.properties`，把 `sdk.dir` 指向自己的 SDK。不要提交本机路径、签名私钥或用户照片。

## 构建应用

仓库在 `app/src/main/jniLibs/` 提供原生库；如需修改引擎，先执行下一节。

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
# 仅 ARM64 手机包：
./gradlew :app:assembleDebug -PfilmroomAbi=arm64-v8a
# 需要实际 Android 测试设备；既有交付记录未完成此项：
./gradlew connectedDebugAndroidTest
```

Release 默认未签名。请用自己的密钥签名并保管好密钥；不同签名不能覆盖安装已有应用。既有体验包为调试签名，不代表已建立正式发行证书流程。源码不含私人签名密钥。

## 重建与替换全部 JNI 组件

先阅读[引擎构建说明](../native/构建说明.md)与[编解码器说明](../codecs/README.md)，按要求配置官方工具链。

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
bash native/build-native.sh --check
bash native/build-native.sh all
bash codecs/build-android.sh
for abi in arm64-v8a x86_64; do
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "native/artifacts/jniLibs/$abi/libfilm_engine.so" "app/src/main/jniLibs/$abi/"
  cp "codecs/artifacts/jniLibs/$abi/libfilm_codecs.so" "app/src/main/jniLibs/$abi/"
done
./gradlew testDebugUnitTest lintDebug assembleRelease
```

引擎保留锁定版本和实际编译依赖源文件，完整可选依赖图首次解析可能访问 crates.io；codec 附有离线 vendor。缺少对应 ABI 的引擎时，应用禁用效果与导出，不提供假滤镜回退。

## 处理与验证边界

- 交互预览最长边 512，停稳后内存允许时重绘到 768；低内存时使用 512/256。局部放大预览最长边 1536，导出走独立数据路径。
- JPEG/PNG 原尺寸处理使用分条解码、磁盘临时数据和原生计算。HEIC 需整图内存准入，解码为线性工作文件后供预览和导出共用。
- DNG/HEIC 导入上限 52MP，最终编码上限 84MP，仍受格式、内存与空间限制；不是所有输入都能达到该上限。检查点额外空间按 48 字节/像素加安全余量预留。
- 后台队列支持受限并行、前台预览协作和阶段恢复；系统/厂商策略仍可终止服务，强停后不自启，中断任务由用户继续或丢弃。
- 可选 Adreno 路径仅加速受限密度计算，**不是全流程 GPU 加速**。保留 CPU 路径。
- 既有证据是 Linux 主机算法、JNI、Android View/Skia 测试，以及 Android 交叉编译/ELF 检查；不等于真机验收、手机性能或耗电测量。
- `codecs/tests/public-fixtures` 是带来源与哈希的 CC0 测试照片，不是用户照片，也不进入 APK。

查看[验证记录](../验证记录.md)、[主机 UI 验证](UI_HOST_VERIFICATION.md)、[局部预览](LOCAL_DETAIL_UI.md)、[后台导出](../BACKGROUND_EXPORT.md)、[原生准入](../NATIVE_WORK_ADMISSION.md)与[输出色域契约](../OUTPUT_COLOR_CONTRACT.md)。历史记录中的旧阶段数或旧格式描述不覆盖后续版本的能力契约。

## 报告问题

提供应用版本、Android 版本、设备、复现步骤、预期与实际结果。日志通过应用内「诊断日志」手动导出单个 UTF-8 TXT；可选最近 5/15/30 分钟，或 1–1440 分钟。日志最多保留 24 小时、1 MiB，容量达到上限可能提前覆盖；它不是系统崩溃报告。

日志不记录照片、文件名、GPS、EXIF、路径、content URI 或账号。不要把含私人照片/位置的原图直接贴到公开 Issue；复现文件请使用有权分享的脱敏或公开样本。

## 许可与分发

修改和分发时保留 [GPL-3.0](../LICENSE)、上游许可、胶片档案/LUT 的 CC BY-SA 4.0 声明及[第三方 notices](../app/src/main/assets/licenses)。原生对应源码、构建方式和数据声明应与可执行文件一起提供。品牌名称仅识别配置，不暗示品牌背书。

完整旧 README 已逐段保留在[历史开发记录](HISTORY.md)，便于追溯各版变化，而不是堆在产品首页。
