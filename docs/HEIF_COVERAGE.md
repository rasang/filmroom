# HEIF 覆盖范围与兼容性验证

审查时间：2026-11-04。正式基线：9dea3248；候选新增 HeicPrimaryInput 与导入接入。以下区分实现范围、已完成主机验证与未完成设备验证。

## 一页覆盖矩阵

“已实现”指应用有准入及处理路径，不保证每台 Android 系统解码器都成功。Android 官方格式支持表与 ImageDecoder 能力查询均不能代替具体文件、设备的完整解码。[1][2]

| 标准能力/变体 | 正式 0.1.20 | 本次候选 | 限制与后续工作 |
|---|---|---|---|
| 静态 HEVC hvc1 主图；Main/Main10/Main Still profile 1/2/3 | 已实现 | 保持 | 实际必须8bit、4:2:0；profile2不等于允许10bit |
| grid 分块主图 | 已实现 | 保留主图及有序 dimg tiles；核对 grid payload | 不采用低分辨率缩略图；保留编码payload，仍需全图解码 |
| irot 容器旋转 | 已实现 | 原属性保留 | 由 ImageDecoder 应用一次，不再按EXIF旋转 |
| imir、clap、非方形pasp | 拒绝 | 仍拒绝 | 需要独立、精确的几何实现及真实像素测试 |
| 标准 sRGB / Display P3 matrix/TRC ICC | 已实现 | 原ICC保留 | P3转换到8bit sRGB工作空间；不是广色域保留 |
| 已验证精确量化 rounded P3 TRC | 已实现 | 原描述符保留 | 数值白名单，不按手机品牌、文件名或ICC名称放行 |
| NCLX sRGB (1,13,1) | 已实现 | 保持 | NCLX-only P3/BT.2020、未知transfer仍拒绝 |
| SDR primary + tmap HDR alternate + 独立 gainmap | 全局HDR属性/tmap导致拒绝 | 新增受限 SDR primary-only decoder view | 只接入明确 primary-first dimg(base,gainmap)图关系；不应用/保留HDR层；不是完整tmap/HDR支持 |
| 原件底图自身10bit、PQ/HLG/HDR | 拒绝 | 仍拒绝 | 需高位深线性工作空间、明确映射/显示/导出策略，不能靠移除HDR标签解决 |
| 精确 Apple auxC HDR gainmap + auxl→SDR primary | 原全局检查拒绝 | 新增受限 primary-only view | 仅隐藏 hvc1 gainmap；严格 SDR 主图、原 ICC/方向保持；无 HDR 保留，待 Android 实测 |
| alpha/depth等auxl/auxC标准辅助图 | 拒绝 | 关联所选主图仍拒绝 | 不可静默丢alpha；深度保留另议 |
| 动画、image sequence、视频轨 | 拒绝 | 仍拒绝 | moov/moof及序列品牌被拒绝；没有帧选择/时间轴 |
| AVIF / 其他HEIF编码、通用mif1 | 不走HEIC实现 | 不新增 | HEIF是容器体系，不等于全部使用HEVC；AVIF标准有独立codec要求[4] |
| iloc位置与item IDs | 原路线交系统解码 | 新remux仅内部method0/1、所选id≤65535、支持0/4/8字节字段 | 外部data_reference、method2与更宽ID明确拒绝；不是完整ISO-BMFF编辑器 |
| 私有厂商uuid/额外rendition | 不解释 | 在SDR view中移除，原件保留 | 不读取厂商公式；不声称HDR/运动照片等扩展可往返 |
| EXIF、原件保留与恢复 | 已有受控元数据读取和spool恢复 | 元数据继续来自原件 | 并非完整元数据往返；恢复不能被新临时view的空间需求阻断 |
| 输出/动态范围 | 原尺寸SDR8→线性RGB spool | 保持 | 不新增HEIF导出，不保留HDR、高位深或sRGB外色域；现有UI提示明确[3] |

## 跨品牌通用方案

按容器品牌、item类型、引用图、编码深度、数值色彩描述符和几何契约判定；不使用 vivo/OPPO/iPhone 等型号白名单。先核验 primary 的 SDR 合同，再隔离其精确编码payload和关联属性，避免系统根据 altr 自动选择另一个 rendition。ISO-BMFF 的 altr 描述了有序替代项选择；本样本恰为 tmap 在 SDR base 之前。[5]

“通用”意味着同样结构的跨品牌文件走同一路径，不意味着支持所有HEIF标准功能。应以标准变体清单逐项扩展，每项包含正常样本、恶意/冲突样本、主机像素证据及Android实际解码。HDR完整支持必须作为单独项目，覆盖解析、颜色管理、高位深工作空间、gainmap方向、编辑后gainmap重建和HDR输出。[3]

## 来源

[1] Android 支持格式：https://developer.android.com/media/platform/supported-formats

[2] ImageDecoder：https://developer.android.com/reference/android/graphics/ImageDecoder

[3] Android Gainmap：https://developer.android.com/reference/android/graphics/Gainmap

[4] AOM AVIF v1.2（HEIF-derived grid/tmap及AV1专属约束须区分）：https://aomediacodec.github.io/av1-avif/

[5] AOM/libavif 对ISO14496-12:2022 §8.15.3.1的引用和altr选择实现：https://aomedia.googlesource.com/libavif/+/d5c8bcf2353fd9de77ec4bce760b760328fee0f4

实现依据：app/src/main/java/dev/filmroom/io/{HeicContainer,HeicIccProfile,HeicImport,HeicPrimaryInput}.kt、importing/HeicInputAdapter.kt、lifecycle/PrivateWorkRecovery.kt、docs/HEIC_IMPORT.md。此说明不包含私有照片、EXIF或像素。


## 最终复核结论（2026-11-04 14:47 UTC）

生命周期与资源边界已复核：Prepared.info使用同一严格Reader进行内存metadata准入，实际.file延迟生成；完整spool恢复无需写临时HEIF；低空间明确Storage分类；PrivateWorkRecovery窄模式回收临时view。异常/取消通过use和catch清理，进程中断由启动回收覆盖。

最终生产diff没有放宽ICC/TRC、深度或主图几何限制，没有按厂商名或照片hash放行；原始ICC/hvcC/ispe/pixi/irot及所选编码payload保持原值。HeicContainer只抽取File/Memory Input适配器，共享原有检查；没有第二套宽松颜色检查。heix品牌按原输入保留。

已完成的聚焦验证：
- 纯JUnit：70 tests passed，包括旧容器/ICC 35、新primary view 27、恢复8。
- Android host：49 tests passed，包含新tmap repository import/restore与失败清理。此为host/Robolectric，不是Android设备HEVC成功。
- 编码字节核对：两张原件各49个选中items，payload与关联property列表逐字节一致，dimg顺序一致，alternate/private boxes从view排除。
- 主机完整像素核对：3072×4080与4096×3072完整RGB解码hash分别与原件一致，ICC经LittleCMS到sRGB的全像素hash亦一致。独立数学抽样最大误差不超过一个8bit码值。

结论：在本次静态审查与已运行聚焦测试范围内，没有剩余确认阻塞。可作为待目标设备验证的兼容候选，不能称为所有Android成功、完整HDR保留或完整HEIF实现。本次未生成新APK，未进行目标Android真机/模拟器解码，未运行全工程Gradle/native发布门禁；之前测试运行环境配置失败不计为通过。
