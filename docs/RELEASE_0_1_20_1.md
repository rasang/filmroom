# Filmroom 0.1.20.1

HEIF 导入正式修复版，基于 0.1.20。版本代码 35，Android 10+ / ARM64，沿用原发行签名。本版只包含本次 HEIF 修复；不包含正在单独开发的 GPU 加速。

## 修复

修复带标准 tmap HDR 增益图/替代图的 HEIF 被全局 HDR 属性检查过早拒绝的问题。处理时只读取已验证的原尺寸 SDR primary，在私有临时 HEIF 视图中排除附加 HDR rendition；原始文件不改动，元数据仍从原文件读取。采用标准 item/引用图/颜色数值规则，不按 vivo 或其他厂商名字特判。

原 HEVC 主图/grid/tile 编码字节、顺序、ICC 和旋转属性保留，不重新压缩、不换缩略图、不套用 HDR 增益或 tone-map。视图在成功、失败或取消后回收，进程中断孤儿由启动恢复清理。已有线性工作文件的恢复不重新物化临时 HEIF，避免低空间阻断恢复。

## 范围与验证

- 两张实际问题样本的全部 25,116,672 主图像素，在主机 libheif 中与临时视图逐像素一致；实际 ICC→sRGB LittleCMS 输出 hash 也完全一致。保留的 49 个 item payload/属性和 dimg 顺序逐字节核验。
- 修复候选的70项容器/ICC/视图/恢复纯JVM测试及49项Android host颜色/诊断/生命周期测试通过。新结构负例覆盖主图HDR/10bit、unknown essential、引用、位置越界/溢出、多个extent、idat、grid冲突、取消及低空间。
- 同一应用逻辑的冻结候选已完成完整主机套件：210 套、1052 项通过、57 项跳过、0 失败/错误。正式版仅改变显示版本、内部升级编号和文档，逐文件核验应用代码、测试与资源不变；重新执行 lint、Release 构建、签名与独立验包。未将旧源码哈希的全量运行冒充为正式版新哈希重跑。
- **目标Android真机安装升级、实际ImageDecoder全尺寸导入/颜色和完整导出仍未实测。** 主机真实ImageDecoder的HEVC实现仍报`unimplemented`，与Robolectric替身像素生命周期测试分开记录。
- 本版只处理SDR底图，不应用或保留HDR增益图，输出仍为既有SDR路线。不是完整HDR、高位深或所有HEIF变体支持；[完整能力矩阵](HEIF_COVERAGE.md)。
- 原生引擎和codec二进制与正式0.1.20逐字节相同；不重跑完整原生/真实RAW压力矩阵，不增加原生依赖、权限或网络行为。

## English

Stable patch release for standard gain-map/tmap HEIF files with a separately declared SDR primary. The app creates a private primary-only container view for decoding, preserving the exact coded payloads, tile order, ICC and orientation. It does not modify the original file, re-encode pixels, replace the image with a thumbnail, apply the gain map or tone-map HDR. Temporary views are cleaned after use and on process recovery; retained linear spools reopen without writing another encoded view.

The route is structure-based and cross-vendor, not a model-name whitelist. It remains an SDR import path with explicit limits. Full host decoding and independent color reference checks do not establish target-device HEVC success. Physical Android installation, full image decode/color and end-to-end export validation remain outstanding. Native engine/codecs are identical to stable 0.1.20, with no GPU experiments. The identical application code passed 1,052 host tests, with 57 skipped. This promotion changes only version metadata and documentation; byte-for-byte source equivalence is recorded, followed by fresh lint, release build, signature and independent package verification. The full suite was not rerun under the final metadata hash.

Display version 0.1.20.1 follows this project's patch-release naming; it is not a three-component SemVer. Android version code 35 remains monotonic and is newer than the earlier candidate's code 34. Physical-device upgrade validation is still outstanding.
