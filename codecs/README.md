# 本地照片编解码组件

此组件完全离线，用于原生安卓“暗房”的 RAW 导入及 JPEG / TIFF 导出。它不联网，不使用嵌入 JPEG 代替 RAW，不重建或伪造 RAW，不自动降低照片尺寸。

## Beta5 RAW 支持范围与限制

DNG 保留既有 16-bit 路径，并新增经过样本验证的 12/14-bit、三通道 LinearRaw SOF3 无损 JPEG：predictor 1、无点变换、全分辨率通道、按行对齐的重启间隔及有界条带解码。新校准路径支持经验证的 LinearizationTable、ProfileGainTableMap 和双光源 ColorMatrix/ForwardMatrix 校准；增益表可缺省。4 份 S23 系列公开样本（其中 1 份明确为 Expert RAW，其余拍摄模式未知）通过整幅原始像素及线性色彩对照。这不等于所有三星/Pro/Expert RAW 模式均支持。

Panasonic .RW2 已验证 v4、v5（14-bit）、v6、v7（14-bit）及受约束的 v8/CF2 路径，共 13 份样本；新增 v8 样本涵盖 GH6、S5 II X、G9 II、GH7、S1R II 的已测模式。v7/12-bit、非恒等 CF2 gamma/非零 shift、未实现的编码或校准方式仍明确拒绝，不回退到内嵌 JPEG。

DNG 单次操作预算为 128 MiB；相机 RAW（含 RW2）为 256 MiB，并受共享内存准入控制。最大输入为 52 MP，超限拒绝而非暗中缩图。新 DNG 12/14-bit tiled 布局、JPEG-XL、其他未实现 SOF3 布局及必需但不支持的处理操作不在本次支持范围内。样本通过不能泛化为整个品牌或全部机型/模式的兼容保证。

上述结果为主机验证，不等于 Android 真机验证；完整原始文件未随源码分发。

## 既有 16-bit DNG 路径及通用输出

下列 DNG 矩阵、校准与 SOF3 限制描述既有 16-bit 路径；Beta5 新 12/14-bit LinearRaw 路径按上一节单独准入。


普通 NEF / CR2 / ARW 内容识别与有界条带开发路线的准入/验证边界见 `../docs/CAMERA_RAW_IMPORT.md`；其当前工作预算与 RW2 同为 256 MiB，不代表所有机型/变体已通过。

- DNG 导入：经典 TIFF 容器、大小端、无压缩 16 位整数条带，或已验证的 16 位无损 JPEG（SOF3）平铺 / 条带；四种 2×2 拜耳排列或三通道线性 RGB；有效的黑白电平、AsShotNeutral、D65 ColorMatrix；最多 5200 万传感器像素，每边不超过 32768。
- DNG 使用 ActiveArea、DefaultCrop 和方向一次，标准 BaselineExposure 曝光基准在线性 RGB 中只应用一次，输出最终方向的线性 sRGB float32 RGB 私有文件。相机自带 ProfileToneCurve 显示曲线与 ProfileHueSatMap 色相饱和度校正表不套入线性胶片流程，并向用户明确提示颜色可能与相机或其他软件不同；不是相机直出 JPEG 色彩复刻，也不宣称完整 Adobe DNG 开发模型。
- 只接受正对角或单位 CameraCalibration，显式加入选中的 D65 ColorMatrix；RawDevelop 的逐行归一化使正对角缩放消除。其他通道混合校准暂不支持。ForwardMatrix 为另一种转换方法，保留提示而不与 ColorMatrix 重复使用。噪声描述不等于必需降噪指令。
- 明确标记 optional 的 DNG 操作可跳过，给用户显示可能保留暗角、畸变的警告。必需但未实现的操作、损坏操作包直接报错，不静默忽略。
- JPEG 导出：8 位、4:4:4、用户质量 1–100、默认 95；逐行基线编码。禁用需要整图 DCT 缓存的渐进/优化 Huffman 模式。
- TIFF 导出：16 位 RGB、无压缩 32 行条带。保留引擎浮点结果的较高量化精度，不是把 8 位预览拉伸为 16 位。
- 两种导出都显式转为标准 sRGB、SDR 范围裁切，记录裁切样本数并嵌入生成的 sRGB ICC。原文件元数据、GPS、作者信息不复制。
- 无损 JPEG 仅支持 16 位、无降采样、无重启标记、单扫描、点变换为 0 的 1–4 分量布局；每块最多 400 万样本且压缩数据不超过 8 MiB。按交叠块解码，不分配整幅 RAW；内存预算计入块缓冲和解码器开销。读取完整熵流并检查结束标记，截断不会被补零伪装成有效照片。
- 其他压缩方式、无压缩平铺、浮点 DNG，未通过本轮变体验证/准入的相机 RAW、TIFF/EXR 导入、EXR/HEIF 导出不在此组件支持范围。上层可接入实际存在的系统解码能力，但必须单独标示。

## 构建

安装官方 Rust 工具链、JDK 17、Android NDK 27.2。本目录包含锁定版本的离线 vendor 依赖及完整许可证。

1. `cargo test --offline --locked --release -j2`
2. `cargo build --offline --locked --release --lib --bin codec_probe -j2`
3. `JAVA_HOME=/你的/JDK ./run-host-jni.sh`
4. 设置 `ANDROID_NDK_HOME`、`CARGO_HOME`、`RUSTUP_HOME` 后运行 `./build-android.sh`。

产物是 `artifacts/jniLibs/arm64-v8a/libfilm_codecs.so` 与 `x86_64/libfilm_codecs.so`。Android API 26，ELF 页面按 16 KiB 对齐。最终源代码和 APK 必须与最终清单中的 SHA256 一致。

## JNI 生命周期

`codec-v1.json` 是接口契约。每个 handle 的取消是粘性的，不会在新调用开始时清零。取消后创建新 handle；活动工作返回后再 destroy。每个 handle 同时只允许一个操作，其他调用返回 BUSY。上层必须在创建/发布 handle 的同一受控区间登记取消，不能持锁等待长时间 JNI 操作。工作失败或取消会尽力清理半成品；进程强杀后的残留由应用受限范围启动恢复清理。

JNI 错误为顶层 `ok:false, code, message, stage, outputValid:false`，不是命令行工具的嵌套 error 结构。路径、原始异常、EXIF 和图像内容不得进入日志。

## 验证边界

真实执行的是云端 Linux Rust/JNI 与生成/公开测试照片。Android ABI 是交叉编译和 ELF 检查，不等于安卓设备端运行。最终量测和用例见 `results/` 与最终清单。公开手机样例来自 raw.pixls.us 的 CC0 数据，来源及哈希见 `tests/public-fixtures/SOURCE.json`；不属于用户照片。

### Failure-only technical diagnostics

Failed DNG probe/decode replies (not invalid-path/handle, busy or cancellation
replies) may include a top-level `technical` object. It contains only a fixed
numeric schema, revalidated by the Android `RawTechnicalInfo` whitelist before
being added to local shared diagnostics. `rawProbeStatus` is 0 for a complete
classic-TIFF layout scan, 1 for malformed/unsupported layout, 2 for a diagnostic
resource bound. This status describes the diagnostic scan, **not decoder support**.
No filename, textual metadata, EXIF/GPS traversal, camera strings, matrix values,
image payload or arbitrary native error message is included.

The independent failure probe reads at most 64 KiB total, traverses at most 16
IFDs with 256 entries each, and permits at most 4096 block-byte counts. It selects
the largest non-reduced CFA/linear-RAW IFD (otherwise the largest image IFD); per-image fields
are not mixed across IFDs. Calibration presence and opcode lists inherit from
the root IFD with per-tag RAW overrides, matching decoder semantics. It reports dimensions, compression/bit depth, CFA
repeat, tile/strip layout, largest encoded block and count, DNG version (packed
big-endian bytes), selected IFD entry count and calibration-tag presence bits
(bit 0 = CameraCalibration1, bit 1 = CameraCalibration2). Opcode lists report
aggregate declared bytes/count and the first required opcode's ID/version/flags;
only up to 64 fixed-size headers per list are read, with payloads skipped. Partial
facts can survive malformed metadata, accompanied by nonzero probe status.
These observations do not change admission limits or promise support for an
unseen RAW variant. In particular, 8192×6144 is below the 52-million-pixel guard,
but single lossless-JPEG blocks still have their separate sample/byte limits.


### Camera-profile HSV table handling

ProfileHueSatMapData1/2 (50938/50939) are intentionally not applied by the
matrix-based basic RAW development. This is a DNG color correction, distinct
from ProfileLookTable; omitting it can produce colors different from the camera
or other DNG software. Their TIFF type, positive count and on-file
byte range are checked without allocating/reading table payloads. DNG validation
checks ProfileHueSatMapDims and the exact 3×Hue×Saturation×Value count, rejects
missing/mismatched tables, and emits `DNG_CAMERA_HSV_LOOK_NOT_APPLIED`. This is
descriptor-shape/bounds validation only: the skipped float values are not
validated. It is not full DNG camera-profile rendering. Mandatory sensor
calibration/opcode checks and all pixel, block and memory admission limits stay
unchanged. Other loaded tags retain the existing 64 KiB per-tag bound.

Regression coverage uses synthetic 172,800-byte profile tables and verifies
byte-identical linear output versus the same fixture without the omitted HueSatMap correction,
plus malformed count/type/dimensions/offset rejection. A privately supplied
50MP sample was decoded at full oriented 6144×8192 resolution on the Linux host;
its photo, preview and derived pixels are not included in this repository.


## JPEG XL DNG in 0.1.20-beta.1

The earlier JPEG-XL exclusion is superseded only for the validated 16-bit, three-channel LinearRaw strip contract described in [JPEG XL DNG](../docs/JPEG_XL_DNG.md). This route uses official libjxl 0.12.0 and can request up to 384 MiB, subject to system memory and shared admission. Other DNG remains at 128 MiB and camera RAW at 256 MiB. This does not cover tiled, CFA, floating-point, other bit-depth, extra-channel or animated JPEG XL inputs. Android device validation remains pending.

此前 JPEG-XL 不支持的说明，仅由本次已验证的16位三通道 LinearRaw 条带路径取代。该路径最高申请384 MiB，仍受系统可用内存与共享准入限制；其他DNG128 MiB和相机RAW256 MiB不变，未宣称所有三星模式或真机验证通过。

- Q2 实样对应的必需坏点修复、分通道几何校正与 A/D50 CFA 色彩扩展及资源边界见 [DNG-Q2-REQUIRED-CORRECTIONS](docs/DNG-Q2-REQUIRED-CORRECTIONS.md)；这是受限结构支持，不表示全部 Leica/DNG 变体已支持。

## Fujifilm RAF candidate

现代 Bayer / X-Trans RAF 的内容识别、未压缩行读取与受限无损/有损传感器解码，详见 [RAF 范围与验证](../docs/FUJIFILM_RAF.md)。X-Trans 为单独的保守 6×6 基础插值，不套用 Bayer 算法，不复刻富士直出。52 MP 传感器上限和 256 MiB 相机预算不变；GFX 50S/100 II 样片超限拒绝，旧式 SuperCCD/EXR/双布局拒绝。通过的样片不能泛化为所有 RAF。
