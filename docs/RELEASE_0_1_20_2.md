# Filmroom 0.1.20.2

基于 0.1.20.1 的正式 HEIF 导入修复，versionCode 36，原 applicationId 和发行签名不变。没有 GPU、RAW 或其他开发分支的改动，不增加 codec 依赖。

## 原因与修复

0.1.20.1 生成的 SDR 主图临时 HEIF 视图将 `ipma` 属性关联表按“主图在前、tile 在后”写入。当主图 item ID 大于 tile ID 时，这不符合 ISO/IEC 23008-12:2017 §9.3.1 的递增 item_ID 要求。Android 17 的 CrabbyAvif 解析器明确拒绝这种表，即使关闭可选严格检查也一样。先前主机 libheif 接受了该结构，不能据此推断 Android 平台接受它。这是应用重封装实现错误，不是厂商名称或照片内容问题。

本版仅将输出 `ipma` 的条目按 item ID 递增写入。每个 item 的属性列表、essential 标志及属性内容完全不变；`iref/dimg` 的 tile 拼接顺序、`iloc`、`mdat`、主图、编码数据、ICC、旋转与原件均不改动。不能把 tile 本身按 ID 排序，否则会改变图像。

## 证据与边界

- 使用 Android 17 所含 CrabbyAvif commit `c718caf5bc658d3f806a442666800781c66a5274` 的未修改 Rust 解析源码，按 Skia 的 `ignoreExif`、`ignoreXMP`、`strictFlags=DISABLED` 设置编译主机解析探针。
- 两张实际问题原件均解析通过；0.1.20.1 生成的两张视图均复现 `ipma item ids are not ordered by increasing id`；新生产代码生成的两张视图均解析通过，尺寸、8 位深度和 ICC 长度正确。
- 此探针执行实际 Android 17 同源解析器，不执行 Android MediaCodec 或手机系统 ImageDecoder。**它不是目标手机安装、完整像素解码、颜色或导出的实测。** 用户原件和私有日志不包含在源码或 APK 中。
- 新合成回归覆盖大 ID 主图和非递增 tile 引用，避免以后把属性表顺序与拼图顺序混淆。最终完整主机测试、lint、Release 构建及独立验包的实际结果见本版发布说明。
- 继续只处理原尺寸 SDR 底图，不应用或保留 HDR 增益图；不新增完整 HDR 或所有 HEIF 变体支持。

## 官方源码

- [Android 17 CrabbyAvif ipma 解析及标准顺序检查](https://android.googlesource.com/platform/external/rust/crabbyavif/+/c718caf5bc658d3f806a442666800781c66a5274/src/parser/mp4box.rs#1344)
- [Skia 移除旧 HEIF codec，改用 CrabbyAvif](https://skia.googlesource.com/skia.git/+/cb2be106f4ee69a32185b1ea8a0c59a429fab267)

## English

The 0.1.20.1 primary-only view wrote `ipma` associations in primary-first order instead of increasing item-ID order. Android 17's CrabbyAvif rejects this invalid table before pixel decoding. The previous host libheif check tolerated it and therefore missed this application bug.

This patch sorts only the `ipma` entries. It does not sort `dimg` tile references, move coded payloads, change per-item properties, re-encode pixels or modify the original. The exact Android 17 parser source reproduces both old-view failures and accepts both newly generated views. Target-device ImageDecoder/MediaCodec pixel decoding remains unverified; parser success is not reported as device success. SDR-only behavior and all existing color and geometry restrictions remain unchanged.
