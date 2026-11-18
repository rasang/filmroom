# Filmroom 开发指南

[简体中文 README](../README.md) · [English README](../README.en.md) · [历史记录](HISTORY.md)

本指南包含 0.1.17 原生构建工具链更新。应用离线运行不等于构建完全离线；首次依赖解析可能需要网络。以下为构建步骤，不表示本次文档更新重新运行了这些测试。

## 环境

- 完整 JDK 17，Android SDK 35，Build Tools 35.0.0。
- 仓库自带 Gradle Wrapper 8.11.1。
- 重建原生组件：Android NDK r27c（27.2.12479018），以及 `aarch64-linux-android` / `x86_64-linux-android` 目标。引擎脚本锁定 Rust/Cargo 1.88.0；0.1.17 编解码器使用独立的 Rust/Cargo 1.94.0 构建与前后对照验证。这里记录实际构建版本，不声明两者是同一工具链或最低支持版本。
- 最低 Android 10 / API 29；默认 ABI 为 ARM64 与 x86_64。

自行创建 `local.properties`，把 `sdk.dir` 指向自己的 SDK。不要提交本机路径、签名私钥或用户照片。

## 构建应用

此开发分支不提交预编译 JNI `.so`。首次构建 APK 前，必须先按下一节从对应源码构建引擎及编解码器，并复制两种 ABI 的库到 `app/src/main/jniLibs/`。仅运行 Gradle 不会自动重建原生库。

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
# 仅 ARM64 手机包：
./gradlew :app:assembleDebug -PfilmroomAbi=arm64-v8a
# 需要实际 Android 测试设备；既有交付记录未完成此项：
./gradlew connectedDebugAndroidTest
```

Release 默认未签名。请用自己的密钥签名并保管好密钥；不同签名不能覆盖安装已有应用。0.1.16 及以前体验包使用旧调试签名；0.1.17 起使用新的固定发行签名，首次迁移须卸载重装。发行私钥在仓库外保管，不由 Gradle 自动生成或提交。源码不含私人签名密钥。

## 重建与替换全部 JNI 组件

先阅读[引擎构建说明](../native/构建说明.md)与[编解码器说明](../codecs/README.md)，按要求配置官方工具链。

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
RUSTUP_TOOLCHAIN=1.88.0 bash native/build-native.sh --check
RUSTUP_TOOLCHAIN=1.88.0 bash native/build-native.sh all
RUSTUP_TOOLCHAIN=1.94.0 bash codecs/build-android.sh
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

## 修改 vendored rawler 后的缓存失效（必需）
Cargo 把 registry/vendor 来源视为不可变；即使改了源码和 `.cargo-checksum.json`，
已有 rlib 也可能不重编。仅 `cargo clean -p rawler` 还可能留下 release 产物。
`codecs/build-android.sh` 现在在每个目标的 release 构建前调用受控指纹检查：
对完整 `vendor/rawler-0.7.2` 字节计算 SHA256，变化或首次接管缓存时，只清理
当前 target/profile 的 rawler（release 明确 `--release`），不全清依赖。

Host 构建也必须先运行同一检查，保持同一 `CARGO_TARGET_DIR`，例如：
```
cd codecs
python3 tools/ensure-vendor-fresh.py --profile dev
cargo test --offline --locked
python3 tools/ensure-vendor-fresh.py --profile release
cargo build --offline --locked --release --lib
```
可加 `--check` 做不改缓存检查：未记录或指纹变化返回2，匹配返回0。
记录位于目标目录 `filmroom-vendor-stamps/`；它只证明成功失效缓存，
不是编译成功或测试通过证明。构建期间须冻结源码并串行操作该target；
失败时保留日志，最终核对源码清单、`Compiling rawler` 与新库SHA256。
不要手工伪造指纹标记，不要靠更新时间判断，不需要把registry vendor改成path依赖。

## GitHub Actions 自动构建与 Release 发布流程

仓库内置两条独立的 GitHub Actions 工作流，日常推送分支代码**不会**自动发 Release，只有推送 `v*` 标签（或手动运行发布工作流）才会对外发布版本。

### 1. 两条工作流分工

| 工作流文件 | 触发条件 | 作用 |
| --- | --- | --- |
| [`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml) | 推送 `dev` / `main` 分支（忽略纯 `docs/**`、`*.md` 与 `release.yml` 改动），或手动 `workflow_dispatch` | 编译原生引擎与编解码器、还原并校验私有音效、构建 ARM64 Release APK、重打包与 16KiB 对齐、使用仓库 Secrets 中的发行密钥签名并上传 `filmroom-arm64-signed-apk` 构建产物（保留 14 天）。**不创建 Release。** |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) | 推送 `v*` Git 标签（如 `v0.1.22.2`、`v0.1.23-beta1`），或在网页 Actions 手动填入已有 tag 运行 | 核对标签版本与 Gradle `versionName` 一致 -> 自动复用已完成（或等待正在运行）的同源码树 `Build Filmroom APK` 签名产物 -> 二次校验发行证书指纹、音轨哈希与 ZIP 完整性 -> 生成 `SHA256SUMS` 与下载核验段落 -> 自动发布或更新 GitHub Release。 |

### 2. 标准发版步骤（备忘清单）

1. **升级版本号（两处必须同步）**
   - 修改 `app/build.gradle.kts` 中的 `versionCode`（递增整数）与 `versionName`（如 `"0.1.22.3"` 或 `"0.1.23-beta1"`）。
   - 同步修改 `app/src/test/java/dev/filmroom/OfficialReleaseIdentityHostTest.kt` 中的 `versionCode` 与 `versionName` 断言。
2. **准备版本更新说明（推荐）**
   - 新建 `docs/releases/<versionName>.md`（例如 `docs/releases/0.1.22.3.md`，参考 [`docs/releases/0.1.22.2.md`](releases/0.1.22.2.md)）。
   - 无需手写文件大小与 `SHA-256`：发布脚本会自动在正文末尾追加 `## 下载核验`（包含提交 SHA、精确字节数与 `SHA-256`）；若未创建该文件，也会自动生成带下载核验信息的简版说明。
3. **提交并推送到分支**
   ```sh
   git checkout main
   git merge --ff-only dev   # 若从 dev 合入主干
   git add app/build.gradle.kts app/src/test/java/dev/filmroom/OfficialReleaseIdentityHostTest.kt docs/releases/
   git commit -m "release: prepare 0.1.22.3 (119)"
   git branch -f dev main
   git push origin main dev
   ```
4. **打 `v*` 标签触发自动发 Release**
   ```sh
   git tag v0.1.22.3
   git push origin v0.1.22.3
   ```
   推送标签后无需等待 `Build Filmroom APK` 先跑完再打标签——若对应提交的 APK 仍在编译中，`release.yml` 会自动等待其完成后再取包发布；若已编译完成，约 10–30 秒即可发布完毕。

### 3. 自动规则与防呆保护

- **版本号强校验**：标签名去掉前缀 `v` 后必须与 `app/build.gradle.kts` 的 `versionName` 完全一致，否则工作流立即报错中止，防止打错标签发错包。
- **自动区分正式版与测试版**：`versionName` 含连字符 `-`（如 `0.1.23-beta1`）时自动勾选 GitHub **Pre-release**；不含 `-`（如 `0.1.22.3`）时发为正式版（Latest Release）。
- **重跑幂等更新**：若同名标签的 Release 已存在，再次手动运行 `Publish Filmroom Release` 会自动覆盖上传 APK / `SHA256SUMS` 并刷新 Release 说明。
- **参考音效源更新提示**：`build-apk.yml` 当前从已发布的公开 Release APK 中还原经 `SHA256SUMS` 校验的三条音效文件；只要该参考 Release 保持未删除即可正常构建。
