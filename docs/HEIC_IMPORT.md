# HEIC 导入：原尺寸 SDR 路线与验证边界

## 当前范围

新增 HEIC **导入**，没有新增 HEIC 导出。JPEG / PNG / TIFF 的输出菜单、位深和色彩契约不变。不引入 libheif、FFmpeg 或其他生产依赖；不增加权限或网络。测试素材生成所用的主机工具不打包进应用。

支持候选是静态、非加密、8 位 4:2:0 HEVC（profile 1/2/3；profile 2仍须实际8位）主图（`hvc1`）及由 HEVC tile 组成的 `grid`。容器旋转 `irot` 由系统 ImageDecoder 应用一次。主图之外的 tile 和缩略图不等同于动画；真实 2×2 grid 纳入测试。HEIF 容器不是任何编码都支持的承诺，AVIF 和图像序列仍未接入。

颜色仅接受可核验的 SDR 子集：关联主图的 NCLX 必须为 primaries=1、transfer=13、matrix=1（检查 range 保留位）；或关联的 ICC 是经过有界解析核验的 sRGB / Display P3 primaries v4 RGB matrix/TRC profile（标准曲线，以及下文列明的单一精确量化 SDR 曲线）。ICC 不按名称放行：检查 D50 色度矩阵、已批准的实际参数曲线、白点/Bradford 适应、tag 边界及冲突；未知/LUT/HDR profile 拒绝。grid 主图未声明颜色时，必须全部 HEVC tile 明确声明同一身份；缺失或冲突拒绝。NCLX-only Display P3/BT.2020 仍不接入，避免系统忽略 NCLX 后的 sRGB 回退。

ImageDecoder 的 ImageInfo.colorSpace 是默认输出空间，官方明确不保证等于编码源空间。设置目标之前先做严格descriptor匹配；仅对下文精确rounded Display P3数值能力，允许由平台 ColorSpace.match 对实际矩阵/TRC计算出的命名P3报告，且仍核对矩阵/range。此报告规范化不修改文件ICC，也不把P3像素重标sRGB；实际转换由保留编码ICC的native ImageDecoder完成。P3 超出 sRGB 的颜色不保留；界面明确显示转换提示。原文件及其 profile 不修改。

仍明确拒绝 10 位/高位深、PQ/HLG 主图、未接入的标准容器辅助图（包括深度/alpha；下述独立 tmap SDR 主图隔离除外）、`imir` 镜像、`clap` 裁切、非方形像素、未支持的必需属性与动画/视频轨。**某些手机的普通拍摄 HEIC 也可能带这些辅助项，因此不能宣称兼容全部 iPhone/OPPO HEIC。** 这里的 HDR/辅助项检查针对解析到的标准容器属性；不声称能够识别每家厂商的不透明尾部扩展。SDR 主图导入不解释或传递这些私有扩展，因而不是 HDR 往返。拒绝不修改源文件，也不自动改用内嵌 JPEG、缩略图或低分辨率转换。

## 单一输入保证

1. 保留选择器返回的原始字节私有副本；已有 512 MiB 文件上限、流式复制和取消清理继续生效。
2. `PhotoHeaders` 最多读取 64 KiB 进行候选识别。`HeicContainer` 用随机访问读取有界元数据（meta ≤4 MiB，最多4096 boxes、1024 items/properties），核对主图、HEVC属性、尺寸、关联与变体。头部识别不是完整文件验证，实际像素仍必须由系统完整解码成功。
3. 查询本机 `ImageDecoder.isMimeTypeSupported("image/heif")`。系统没有解码器时明确拒绝，不从 Android 版本/芯片名称推断一定兼容。
4. **一次按原尺寸解码**。只使用 ImageDecoder software allocator，目标 sRGB，不设置 targetSize/sample/crop。解码头和最终 Bitmap 的显示尺寸均必须与容器尺寸/旋转一致；不再次套用 JPEG/EXIF 方向。最终格式要求 ARGB_8888、sRGB；框架报告的 HDR 主图色彩会拒绝。若已核验的 SDR8 底图附带独立 gainmap，则仅移除附层，不应用、不做色调映射；API36及以后另核验 gainmap 方向为 SDR→HDR，HDR→SDR 或不能确认方向仍拒绝。
5. 逐行读取未预乘 sRGB8 像素，透明值防御性合成白底，然后逆 sRGB transfer 写成线性 RGB float32。此 float 文件保持原尺寸，**不是有损 JPEG 中间文件，也不使8位输入凭空变成高位深**。
6. 预览、交互引擎输入、缩放输出、原尺寸队列全部读取同一个规范化线性文件，避免平台两次解码/两种方向规则导致亮度和方向分叉。HEIC 保持 `image/heif` 标记，不标成 RAW/DNG。
7. 缓存文件使用 `heic-source-UUID.rgb`。进程恢复复用完整 spool，不偷偷重新低分辨率解码；丢失/长度不符明确要求重新导入。启动回收理解该文件名，只清理已知孤儿；当前保留源不会被清理。

队列新增内部 `LINEAR_SOURCE` 输入种类描述需要缩放的规范化数据，仍兼容读旧版 `RAW` 种类。原尺寸沿用 `LINEAR`，不是新的输出编码。

## 内存/磁盘

**不使用 BitmapRegionDecoder 作为“HEIC 分块低内存”的保证。** 平台 HEIF codec 可能仍在内部解码整图。

- 最多52,000,000个描述的编码像素；估算同时计入所有 HEVC tile/缩略图的 ispe 像素之和与主图像素的较大值，所以 grid 的边缘 padding 不会按小预览预算放行。
- 重型准入估算为 **64 MiB + 编码像素 ×32 字节 + 原始编码文件字节数**，另由现有共享 limiter 加16MiB余量。包含整图输出、方向中间缓冲及系统 codec/YUV/reference缓冲的保守余量，而非只计算逐行数组。
- 通过现有 `NativeWorkReservations` 与引擎/RAW重型任务共享名额和当前系统内存证据；保留系统threshold +64MiB之后，只准使用剩余可用内存的一半。50MP可能因当前空闲内存不足而拒绝，不暗中降尺寸。
- **这是准入估计，不是第三方/系统解码器分配器硬上限，也不是手机峰值RSS实测。** 容器metadata并不证明码流合法；系统解码器仍是处理不可信压缩码流的边界。
- 线性工作文件需要宽×高×12字节磁盘空间，加16MiB余量；后续导出沿用队列自己的存储检查。
- 同步系统解码未必可即时中止。取消后等待该调用返回再回收Bitmap，清理新副本/工作文件；不会发布取消后的半成品。

## 测试与未验证项

- 原创CC0真实HEIC：普通主图、三个旋转、四tile grid，以及Main10/mirror/真实NCLX-only Display P3拒绝样本，以及原创标准 ICC P3 主图/仅tile声明ICC的grid、独立LittleCMS转换参考。生成、哈希、宿主libheif解码尺寸与角点在 [HEIC_FIXTURES.md](HEIC_FIXTURES.md)。
- 纯JVM测试检查容器边界、大小、溢出、异常属性、方向、grid内存计数和取消。真实fixture用于容器准入测试；不冒充Android像素解码。
- 显式fake HEVC pixel backend验证内存不足先拒绝、原尺寸/旋转坐标、白底合成、源文件不变、失败/取消释放和清理。
- Robolectric repository/queue测试使用真实container + **替身HEVC像素**，验证恢复、同一spool预览/标准/原尺寸输出、队列快照与非RAW标记。
- 提供instrumentation测试用于实际平台解码的尺寸/角点/旋转/grid核验；**本次按用户要求不运行Mac、模拟器或真机**。编译和host测试通过不等于OPPO/高通设备HEVC兼容、峰值内存、50MP性能已验证。

## 官方依据

- [平台格式表](https://developer.android.com/media/platform/supported-formats)：HEIF设备可选，profile能力必须实查。
- [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)：平台能力、目标色彩空间与解码契约。
- [Skia HEIF scanline实现](https://skia.googlesource.com/skia/+/3296f7ba1f66bad209eb6a861a8bcbd89634f6ae/src/codec/SkHeifCodec.cpp)：subset不等同于低内存原图解码。
- [Ultra HDR编辑](https://developer.android.com/media/grow/ultra-hdr/edit)：修改底图后不能盲目复用旧gainmap。

## 独立 gainmap 与 SDR 底图

厂商可能在标准 HEIF items 之外保存私有增益图。此路线只读取经过严格颜色验证的 SDR8 主图；不解析厂商公式、不恢复 HDR，也不传递原 gainmap。若 Android 返回带附层的 SDR Bitmap，在原尺寸/ARGB8888/sRGB 检查通过后 `setGainmap(null)`，随后逐行读取同一底图。UI 明确提示“只导入 SDR 底图，不应用或保留 HDR 增益图”。标准容器辅助 item 的准入范围未扩大。

[AOSP ImageDecoder JNI](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/jni/ImageDecoder.cpp) 先解码基础像素，再独立提取附层；[Bitmap JNI](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/jni/Bitmap.cpp) 的 getPixels 读取基础 SkBitmap，setGainmap 只替换附层引用。[官方 Gainmap 契约](https://developer.android.com/reference/android/graphics/Gainmap) 区分底图与增强层，[Bitmap.setGainmap](https://developer.android.com/reference/android/graphics/Bitmap#setGainmap(android.graphics.Gainmap)) 允许 null 移除。API36 的 HDR→SDR 反向 gainmap 不属于此路线；仍使用 compileSdk35，通过运行时公开 getter 核验方向，缺失/异常安全拒绝。这些源码/API依据不是 OPPO 厂商解码器实测。


## Beta5：Main Still Picture 与精确量化 ICC

- HEVC profile 3（Main Still Picture）进入同一8位4:2:0检查；并非允许10位/HDR或全部HEIF变体。
- ICC descriptor 保留实际 D50 matrix 和实际 type-3 TRC，不以 Display P3 描述字符串或厂商名称放行。
- 标准曲线保持原量化容差。额外允许一个独立核验的 s15Fixed16 五元组：
  `(157286, 62128, 3408, 5046, 2556) / 65536`，顺序是 `(g,a,b,c,d)`，`e=f=0`。
  这是数值能力白名单，不是通用自定义 gamma 支持；三个通道必须使用相同实际曲线。
  与旧版各通道分别近似标准的检查相比，不同通道曲线现明确拒绝，因为 Android Rgb 合同只能表示一个共享transfer。
- colr 的 ICC 只允许声明长度完全相符，或恰好多出固定8字节 `46 50 58 52 00 00 00 00`。
  后者仅是有界解析视图；tag必须完整落在声明的ICC内，不把任意尾部当padding，原片交给系统时不改写。
- grid比较完整descriptor。标准ICC量化值与同义标准NCLX按原已批准容差保持兼容；自定义曲线不等同标准曲线。
- Java头颜色报告先做严格匹配。仅此精确rounded Display P3 descriptor可另外接受运行中ColorSpace.match返回的命名P3，且观察值必须与该平台命名对象相等；未知curve/不匹配matrix、sRGB回退、任意同名自建ColorSpace仍拒绝。文件ICC白名单及数值容差不变。此处不把头颜色报告当作源ICC或源像素，也不据输出标签证明设备ICC正确。

验证层次必须区分：
1. 纯JVM容器/ICC/合成恶意边界测试与原件准入。
2. 主机libheif完整解码、LittleCMS对实际像素转换和独立数学数值对照。
3. Robolectric native ColorSpace 对384个独立LittleCMS参考色阶的源合同/转换验证。
4. 目标设备真正的 ImageDecoder HEVC解码及其源颜色报告。

前三层不代替第四层。本轮host直接调用ImageDecoder时，能力查询返回true，实际nCreate报
`DecodeException: unimplemented`；这不是成功或可忽略的测试。未取得目标设备实测之前，
只能交付有严格失败保护的兼容实现，不能宣称所有Android/OEM已成功导入该变体。


## 诊断版真机反馈后的头颜色合同修复

`HEIC_SOURCE_TRANSFER_MISMATCH` 可对应旧版将 Java 头颜色报告逐项当成编码源 ICC 的检查失败；仅凭错误码不能证明设备返回了命名 P3。

官方依据：
- [ImageInfo.getColorSpace](https://developer.android.com/reference/android/graphics/ImageDecoder.ImageInfo)：返回预计解码Bitmap的空间，不保证是编码空间。
- [GraphicsJNI颜色映射](https://android.googlesource.com/platform/frameworks/base.git/+/refs/heads/android10-dev/core/jni/android/graphics/Graphics.cpp)：先调用ColorSpace.match，匹配则返回命名对象。
- [ColorSpace.match](https://android.googlesource.com/platform/frameworks/base/+/4fda9095ba9b/graphics/java/android/graphics/ColorSpace.java)：其中TRC的d比较允许小于0.002，足以规范化此精确rounded曲线；实现调用运行平台方法，不复制/扩宽这些容差。若某版本不返回预期命名P3，fallback仍拒绝。
- [SkHeifCodec](https://skia.googlesource.com/skia/+/3296f7ba1f66bad209eb6a861a8bcbd89634f6ae/src/codec/SkHeifCodec.cpp)保留mIccData为编码profile；[SkCodec](https://skia.googlesource.com/skia/+/5f3e5240dc364bad6a744d6ea16608e06ecb4883/src/codec/SkCodec.cpp)将fEncodedInfo.profile()传入skcms_Transform。Java报告规范化不替换这个native源ICC。

实现仍只请求一次目标sRGB、一次原尺寸decode；无重新标记源bitmap、无把目标像素当raw重复套ICC。尺寸/配置/输出sRGB/gainmap方向/内存全部保留。API29与35 host合同测试验证ColorSpace.match规范化及负例；现有384色LittleCMS对照验证实际曲线转换而非标准P3曲线。host ImageDecoder nCreate限制尚未消失，这些不是API37 OEM原片像素的端到端实测。需要真机重新导入并对照色块/原图色彩后才可声明该设备兼容成功。


## 标准 tmap HEIF：隔离明确的 SDR primary

0.1.20 的全局属性准入会把同一容器内另一个 rendition 的 HDR NCLX、10-bit pixi 和 gainmap ICC 当作当前主图能力，导致尚未调用 ImageDecoder 就拒绝。一些符合标准的文件同时有 SDR primary 和 tmap 派生 HDR 图，且 altr 把 HDR 图放在前面。仅放宽全局检查不安全：系统可选择另一个 rendition，而最终 sRGB 标签不证明输入没有被 tone-map。

新路径不按品牌、机型、文件名、ICC描述或照片hash放行。普通已支持文件不变；原严格准入明确 UNSUPPORTED 时，才尝试以下标准结构路径：

1. primary 必须是可独立验证的 hvc1 或 grid，并明确是唯一 tmap 的 dimg 第一输入；第二输入必须是不同的 gainmap 图。动画、外部引用、受保护图、未知主图依赖和关联 alpha 仍拒绝。
2. 解析并验证所选 item 的 iloc。支持内部文件 method0 与 idat method1，0/4/8字节字段；16-bit范围的item ID。禁止外部data_reference、method2、越界、溢出、缺失或重复位置和零长度extent。grid payload行列/尺寸、tile数量和覆盖必须一致。
3. 只读生成内存 plan，重新使用同一个 HeicContainer Reader 核验所选主图及tile的全部严格SDR规则。主图自己是10-bit/PQ/HLG、未知ICC/TRC、mirror/crop、未知essential属性时仍失败。已有spool恢复仅读plan，不创建临时文件，不增加磁盘空间要求。
4. 真正解码时流式生成私有 `heic-primary-*.heic`：保留原编码主图/grid/tile payload、dimg顺序及关联ICC/hvcC/尺寸/旋转/essential位，只重写容器索引和iloc offset。剥离tmap/altr、非主图properties、其他items和私有uuid。不是像素解码、JPEG转换、缩略图替代或重压缩。
5. 新文件再过一次严格准入，再交给原 ImageDecoder 路径。因为系统看不到另一个rendition，不能根据altr改选HDR图。原图仍保存并用于PhotoMetadata和恢复；视图在成功、异常或取消后删除，进程中断的孤儿由启动回收按严格文件名清理。
6. 输出仍是原尺寸SDR8→线性RGB float32工作文件，仍明确提示不应用/保留HDR gainmap。本次不实现HDR高位深、广色域保留、编辑后gainmap重建或HEIF导出。完整能力矩阵见 [HEIF_COVERAGE.md](HEIF_COVERAGE.md)。

验证分层：两张原始标准tmap样本在正式源码上均稳定于全局NCLX检查失败。候选每张保留49个item（grid+48 tiles），各item payload与关联属性逐字节一致，dimg顺序一致，原文件hash不变。主机libheif对原始primary与视图完整解码，共25,116,672像素，两对RGB hash与实际ICC→sRGB LittleCMS转换后hash均完全一致。抽样独立矩阵/TRC数学与LittleCMS差异最多1个8-bit码值。

这些证据证明容器选择和主机像素保真，不证明目标Android系统HEVC实现已成功。Robolectric native ColorSpace、输入生命周期、失败清理和恢复测试与真实HEVC probe分别记录；当前host实际ImageDecoder仍在nCreate报unimplemented。没有把替身像素或支持查询true当作真机成功。设备端仍需完整解码、原尺寸/颜色核验，发布前不得略过该边界。

## Apple auxl HDR gainmap：隔离严格 SDR primary

新增一个独立于标准 `tmap` 的受限容器分支。只接受无 `tmap`、唯一 `auxl` 从隐藏 `hvc1` 辅图指向 primary，且辅图具有唯一、版本/flags 均为零的 `auxC`，内容精确为零终止 `urn:com:apple:photo:2020:aux:hdrgainmap`。这是辅助图格式标识，不是设备或文件名白名单。该标识不能单独证明底图为 SDR：所选主图与 tiles 仍经过同一个严格 Reader 的 ICC/TRC、8-bit、4:2:0、几何和资源检查。

只对已核验的这条 gainmap 引用允许隔离；alpha、depth、未知辅助类型、多个/反向/多目标引用、gainmap 作为 tile、其他不明依赖、混合 tmap/auxl 继续拒绝。主图本身的 PQ/HLG、10-bit、未知 ICC、mirror/crop 与未知 essential 属性规则不变。当前不支持可见辅图、grid gainmap 或 auxC subtype 扩展。

复用现有延迟 materialization、流式复制和清理流程。primary + 有序 tiles 的编码 payload、ICC、hvcC、ispe、pixi、irot 和 essential 位保持逐字节相同；只重写容器索引，不转 JPEG、不选缩略图、不重编码。方向仍由 ImageDecoder 应用一次，EXIF 来自原件且不再次旋转。输出仍为原尺寸 SDR → sRGB 工作流，不保留 HDR gainmap 或完整 Display P3 色域。

2026-11-05 主机验证：公开 PhotoPrism `iphone_15_pro.heic` 原件 SHA256 `c68e438f92341da271a731a059113db9395366f4c2f78ead5ded64deba7de463`，主图 4032×3024、irot=3，显示尺寸 3024×4032。隔离 49 items（grid + 48 tiles），所有编码 payload、关联属性列表和 dimg 顺序相同，原件不变。libheif 完整解码的 12,192,768 像素 RGB hash 在原件与 view 间一致；实际 ICC 经 LittleCMS 转换 sRGB 后的全像素 hash 也一致。独立矩阵/TRC 抽样 6,080 像素，差异最多 1 个 8-bit 码值。现有两张标准 tmap 样本仍准入。

聚焦验证：纯 JVM 容器/ICC/主图/恢复测试 77 项通过；Android host 聚焦回归 128 项通过，0 失败、0 跳过（含 Apple 导入、保留原件、spool 恢复及失败清理；host backend 替身像素不作为设备 HEVC 解码证据）。

主机证据不是 Android/OEM 端到端成功证明。目标设备仍须完成原尺寸解码、方向与颜色核验；不宣称所有 Android 设备兼容或完整 HDR 保留。
