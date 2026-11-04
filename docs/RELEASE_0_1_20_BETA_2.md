# Filmroom 0.1.20-beta.2

Development pre-release from `dev`; the latest stable release remains 0.1.19. Android package `dev.filmroom`, version code 30. ARM64 APK uses the existing release certificate.

## 新增 / Changes

- 9 款真实存在过 120 规格的彩色胶卷提供 645、6×6、6×7、6×9、6×17 输出画幅，并支持横竖方向。Portra 800 +1/+2 共用同一物理胶卷素材。
- 120 无齿孔；各画幅从同一条连续片边素材取样，不按画幅重新排列文字或添加漏光。
- 更新批准的 135 + 裸 120 模型缩略图；4 款仅 135 型号保留单卷，15 个处理配置共用 13 张物理型号图片。
- 画幅、方向、裁切和片边版本保存到编辑状态与导出队列，预览、导出、扫描缩略图使用同一几何定义。
- 修复公开 dev 分支残留的旧 DNG 适配器编译错误；实际 RAW/DNG 导入仍使用现有 RawInputAdapter，旧测试覆盖保留。

Nine physical color stocks gain 645, 6×6, 6×7, 6×9 and 6×17 presentation frames, including explicit orientation for square frames. Preview, export, history and queued scan thumbnails share frozen format geometry. Approved 135 + bare-120 cartridge artwork is included. Obsolete DNG adapter files left in the previous public source are removed; the active RAW importer and its tests remain.

## 重要边界 / Important limits

片边为参考重建，不是逐毫米校准的实物扫描。部分胶卷只有局部文字或官方示意图证据，完整编号、间距、两侧相位和颜色并未全部确认。应用内直接显示每款证据限制；不确定内容不当作实物定论。详见 [120 画幅与素材说明](MEDIUM_FORMAT.md)。

Edges are reference reconstructions, not calibrated scans. Partial photographic evidence and schematic references do not establish complete sequence, spacing, opposite-edge phase or measured color. Stock-specific limits are visible in the app. No light leaks are introduced.

本版修改裁切和片边呈现，不改变颗粒、光晕、光学计算或科学参数 `filmFormatMm`，不声称实现完整 120 相机光学模拟。标称几何并非某一具体相机实测片门。

This is a crop/frame presentation feature. It does not change grain, halation, optical calculations or scientific film-width settings, and does not claim complete medium-format camera simulation.

## 验证 / Validation

Validation results are recorded with the published release. Host/Robolectric checks do not constitute a physical-device installation, visual or performance test. Native engine and codecs are unchanged from 0.1.20-beta.1.
