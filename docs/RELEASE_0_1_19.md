# Filmroom 0.1.19

## 中文

正式版，汇总从 **0.1.18 → 0.1.19** 的改进。Android 10+ / arm64-v8a，版本代码 **28**。沿用发行签名，可覆盖升级；建议保留重要照片备份，不要先卸载旧版。

### 新增与改进
- **更多相机 RAW**：新增尼康 NEF、佳能 CR2、索尼 ARW 的已验证解码路径，包含部分特殊编码；新增松下 RW2 的常规及受约束 CF2 路径。读取真实 RAW 像素，不用内嵌 JPEG 替代。支持在设置中调整 RAW 处理批次。
- **三星 DNG / Expert RAW**：扩展已验证的 12/14 位三通道 LinearRaw 解码与色彩校准；S23 系列四份公开样本通过完整像素和线性色彩主机对照，其中一份明确为 Expert RAW。
- **HEIC / HEIF 色彩处理**：支持范围内的 8 位 HEIC 与 P3 ICC 转换到 sRGB，修正把 Android 默认输出颜色空间误当成原图色彩信息的问题。原始 ICC 仍只转换一次，不放宽色彩校验。此次反馈的 vivo 案例用户已确认正常，不代表所有 vivo 或所有手机均通过。
- **照片与预览**：加入混合可视区局部细节预览；修正 RAW 拍摄日期保留；导出初期的黑白轮廓更清晰，并修正导出记录删除后的预览缓存失效。轮廓改动不改变最终成片。
- **黑白胶片与片边**：加入 5222、7266 和 2302 印相材料；修正黑白胶片搭配彩色相纸的中性校准，调整片边、文字与横屏构图细节。2302 按印相材料使用，不替换胶卷片边。
- **导出记录管理**：支持单条删除和清空已结束记录；可仅删记录或同时删除对应导出照片，设置中分别保存偏好。不会删除导入原图；排队、处理中和暂停任务保留；仅成功后才播放移除动画。
- **界面与使用体验**：中性灰配色、横屏构图改进、后台导出设置引导、短错误提示及固定枚举诊断信息。日志不写入原图、ICC 内容、EXIF、文件路径或任意异常文本。

默认原尺寸导出与基础元信息保留是已有功能，不作为本版新增。没有宣称 GPU 加速或大幅导出提速。

### 支持边界
- 格式后缀不是兼容保证；未知编码、必需但未实现的校准或资源超限会明确拒绝，不静默降级成内嵌预览。
- RAW/HEIC 输入上限为 52 MP。松下高分辨率/像素位移超限文件不在支持范围；RW2 v7/12 位、非恒等 CF2 gamma/非零 shift 等变体仍不支持。
- 新 DNG 12/14 位 tiled 布局、JPEG-XL、未实现的 SOF3 布局与处理操作不在范围内。四份三星样本不能代表所有 Pro/Expert RAW 模式。
- HDR 与超出范围的复杂 ICC 仍不支持；局部预览最长边保持 1536，并非原图像素级预览。后台处理仍受系统电池策略限制，连续批量导出可能发热。
- 旧导出记录缺少可靠照片身份时，可使用“仅删除记录”；删除照片仍遵守 Android 系统授权。

### 验证范围
Beta5 功能基线主机套件 1,035 项：979 通过、56 跳过、0 失败；原生联合测试 152 通过、8 忽略。40 份 RAW 与各自参考一致，另有 9 份既有样本与 beta4 输出完全一致。后续 HEIC 修复通过 140 项专项测试。上述是分阶段证据，不相加，也不声称完整套件在正式版重新运行。正式版仅变更版本信息，补跑 14 项启动/头颜色测试全部通过；Lint 0 错误、76 警告，Release 构建通过。

主机测试不等于全品牌 Android 真机验证。用户确认了此次 vivo 导入案例；其他设备、相册、后台策略及内存表现仍需实际反馈。

---

## English

Stable release summarizing **0.1.18 → 0.1.19**. Android 10+ / arm64-v8a, version code **28**, using the existing release certificate for in-place upgrades. Keep backups of important photos; do not uninstall first.

### Highlights
- **More camera RAW:** validated Nikon NEF, Canon CR2 and Sony ARW paths, including selected special encodings, plus conventional and constrained CF2 Panasonic RW2 paths. Actual RAW pixels are decoded, not embedded JPEG previews. RAW batch size is configurable.
- **Samsung DNG / Expert RAW:** validated 12/14-bit three-channel LinearRaw decoding and color calibration. Four public S23-family samples passed full-frame raw-pixel and linear-color host comparisons; one is explicitly identified as Expert RAW.
- **HEIC / HEIF color:** supported 8-bit HEIC/P3 ICC inputs convert to sRGB. Corrects confusion between Android's default decoded-output color space and the encoded source profile. The original ICC is converted once, without relaxed color checks. The reporting user confirmed the vivo case works; this is not blanket vivo or all-device validation.
- **Images and previews:** hybrid visible-region detail previews, RAW capture-date preservation fixes, sharper initial monochrome export outlines, and corrected preview-cache invalidation after export-history deletion. Outline changes do not alter final output.
- **Monochrome film and borders:** 5222 and 7266 films, the 2302 print medium, neutral calibration fixes for monochrome film with color paper, and refinements to borders, lettering and landscape composition. 2302 affects printing, not the selected film border.
- **Export history:** remove individual finished records or clear finished records, optionally deleting their corresponding exported photos. Separate preferences are retained. Originals are never deleted; queued/running/paused jobs remain. Removal animation starts only after success.
- **Interface and usability:** neutral-gray theme, improved landscape composition, background-export settings guidance, shorter errors and fixed-enum diagnostics without source images, ICC payloads, EXIF, file paths or arbitrary exception text.

Default original-size export and basic metadata preservation already existed. No GPU acceleration or major export-speed increase is claimed.

### Limits
- Extensions alone do not guarantee compatibility. Unsupported encoding/calibration or insufficient resources is rejected rather than silently replaced by an embedded preview.
- RAW/HEIC inputs are limited to 52 MP. Oversized Panasonic high-resolution/pixel-shift files, RW2 v7/12-bit and non-identity CF2 gamma/nonzero shift remain unsupported.
- New 12/14-bit tiled DNG, JPEG-XL and unimplemented SOF3 layouts/processing operations remain outside scope. Four Samsung samples do not establish every Pro/Expert RAW mode.
- HDR and unsupported complex ICC profiles remain excluded. Detail previews retain a 1536-pixel longest edge, not original-pixel resolution. Background work remains subject to battery policies; sustained batch export may heat the device.
- Old records lacking reliable photo identity can be removed in records-only mode. Photo deletion still requires applicable Android system authorization.

### Validation scope
The Beta5 functional baseline host suite had 1,035 tests: 979 passed, 56 skipped, zero failed; native integration had 152 passed and 8 ignored. Forty RAW results matched their references, with another nine existing samples bit-identical to beta4. Subsequent HEIC fixes passed 140 focused tests. These are separate historical gates, not additive totals or a claim that the full suite was rerun for stable. Stable changes only version information: all 14 startup/header-color checks passed, Lint reported zero errors and 76 warnings, and the release build passed.

Host tests are not universal Android-device validation. The reporting user confirmed the vivo import case; other devices, galleries, background policies and memory behavior still need field feedback.
