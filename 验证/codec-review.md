# 独立编解码审查（最终源码）

审查范围：`film-photo-codecs`，本机文件格式解析、内存与尺寸上限、取消/残留文件、JNI 入口与色彩语义。只读审查，不修改实现；没有运行 Android 模拟器或真机。

## 当前结论

最终冻结源码未发现未解决的确定 P1/P2 内存安全、尺寸溢出或输出覆盖问题。本文是源码及 Linux host 证据审查，不是 Android 真机或完整 APK 验收。Android ABI 与 APK 对应关系仍由最终集成验收确认。

- TIFF 元数据限制：输入 <= 512 MiB、IFD <= 16、每 IFD <= 256 标签、单标签 <= 64 KiB、累计所读标签 <= 512 KiB；未知描述性标签不复制、不打印。
- 传感器限制：每轴 <= 32768、总像素 <= 52,000,000；关键乘法在受限尺寸之后，所发两 ABI 为 64 位。
- DNG 解码为限行浮点条带 + 8 行 halo，不调用整图 RAW 解码；创建新输出并预分配，退出 guard 尝试删除部分输出。
- JPEG 明确 baseline、4:4:4、非 progressive、非 optimized Huffman，逐行读取浮点数据。TIFF16 每次 32 行，编码后明确 sRGB 转移与 SDR 裁切。
- JNI 根目录约束与 create-new 防止覆盖输入/已有输出；持有 Arc 避免 destroy 时悬空指针。取消标志不被一次新调用静默清空。
- 正对角 CameraCalibration 左乘对应 D65 ColorMatrix 的行缩放，与 rawler 0.7.2 的 `normalize(CM * SRGB_TO_XYZ_D65)` 代数相容；非对角校准仍拒绝。此结论不是完整 Adobe/Camera2 色彩处理等价证明。

## 已提交作者的小问题

1. 能力 JSON 仍写大图测试待运行，缺新错误码/可选 opcode 规则，需要最终重生。
2. NO_SPACE/ENCODE_FAILED 文案声称已清理，但 guard 删除失败被忽略，应改为非保证式文案。
3. DNGBackwardVersion (50707) 已读却未验证，损坏或更高必需读者版本可能通过。应验证类型、长度和实现支持的版本范围。
4. DefaultScale/AnalogBalance 的全 1 检查应同时校验计数，避免空数组 vacuous true。
5. 建议增补 JPEG/TIFF 真正中途取消测试，现测试主要是进入前取消。

## 独立执行证据

`codec-independent/mutation-results.json`：75 个小型结构损坏探测（每个 TIFF 标签分别替换 count、value/offset、type），无异常退出/崩溃。69 个拒绝，6 个接受：3 个是有意忽略的 ImageDescription，另有 NewSubfileType、RowsPerStrip 的仍可解释标量，以及上述尚未验证的 DNGBackwardVersion。

该轮运行的 release probe SHA-256：`5266ea0d89cddd79d70907ec5af90c9def03c8bb9323928c05297612d06d09d3`。这是中间版本证据，不应冒充最终 ABI 检验。


## 最终复核（2026-10-30 18:01 UTC）

上述五项均已解决：能力合同更新；错误文案不再承诺删除必成功；BackwardVersion 验证 BYTE[4] 且必需版本 <= 1.4.0.0；DefaultScale/AnalogBalance 检查长度与有限性；JPEG/TIFF 等到实际写出至少 4096 字节后取消，确认部分输出删除、输入逐块未改。

metadataOnly 仅允许在 nativeProbe 中作为布尔项，返回 admission:null、outputValid:false；nativeDecode/nativeEncode 保持未知字段拒绝，不能用此标志绕过输出资源准入。恢复已经持有的 RAW spool 不必重复要求新解码空间。

最终源码清单 17 项逐个 hash 相符，证据 `codec-independent/final-source-verification.json`，源清单见 codec `results/final-source-hashes.json`。

最终 release probe SHA-256 `5eaede359ac112621cd213a07dd7ce0ad4c61e22adbf8a3f145d9ba70b8f89e3`：重新执行 75 项结构变异，无异常退出/超时/stderr。70 项拒绝，5 项可接受的忽略元数据/仍有效标量；损坏 BackwardVersion 全部拒绝。可复现脚本 `codec-independent/mutation_probe.py` 与 `mutation-results-final.json`。

作者实际执行日志已审查：19 Rust release 测试通过（3 单元 + 11 DNG + 5 export），19 host JNI 检查通过。包括两张真实 CC0 手机 DNG 与 pinned RawDevelop 整图逐样本对照，以及真正中途编码取消。没有将这些测试叫作 Android 运行测试。

独立查看两张从实际浮点解码输出生成的预览：Pixel 2 XL 城市建筑、OnePlus 3T 海滩，未见明显条带接缝、方向损坏或灾难性色道错误。该检查不证明精确色度学/镜头校正正确，也不是 App 截图。

仍须保留明确能力限制：只支持经过验证的未压缩 16-bit strip Bayer/linear DNG；其他相机 RAW、压缩/分块/浮点 DNG、非对角校准和必需未实现 opcode 会拒绝。可选镜头校正跳过必须提示，不能称为所有 RAW 支持。JPEG/TIFF 是 SDR sRGB，有显式裁切，不是 HDR/RAW 重建。
