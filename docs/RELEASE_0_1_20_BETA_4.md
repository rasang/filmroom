# Filmroom 0.1.20-beta.4

Development pre-release from `dev`; stable remains 0.1.19. Android package `dev.filmroom`, version code 32. The ARM64 APK retains the existing release certificate for installation over previous fixed-signature builds.

## 修复 / Changes

- 构图里的横向和纵向合并成一个按钮，固定在 135 画幅前，不随画幅列表滚动；采用已选择的横竖叠框图标。
- 竖屏构图的取消/确认背景稍微变矮，文字、宽度和触控范围保持；窄屏大字时标题与动作自动分行。
- 点击按钮切换方向，保留 6×6 明确方向、135 与无边框行为，以及构图取消、确认和撤销记录。
- Portra 400 的片边品牌文字调整到与 160/800 接近的视觉字重；数字、箭头、位置、间距、底轨和其他胶卷不变。
- 保留全部 25 个历史及当前片边版本，已有队列仍读取原来的精确素材。

One fixed orientation toggle replaces the two separate framed-orientation choices. The selected overlapping-frame icon stays before the scrolling format list. Portrait Cancel/Confirm pills are slightly shorter without reducing text, width or touch targets; the heading stacks at narrow large-text sizes. Portra 400 brand lettering now has a visual weight consistent with the approved 160/800 illustrations; the numbered marks and entire lower rail are unchanged.

## 验证与边界 / Validation and limits

主机交互检查覆盖固定按钮、快速切换、横竖状态、构图取消和恢复，以及窄屏、横屏和中英两倍字体。当前片边的 190 个有效组合核对预览与 float 合成的 sRGB8 像素。Portra 400 高分辨率横竖成片另外对照旧版本，确认变化范围。完整测试、lint 和签名验包结果随发布记录提供。

Host checks cover layout, repeated toggles, explicit orientation, composition transactions, narrow and landscape windows, and Chinese/English large text. Current artwork parity covers 190 supported combinations. Separate Portra 400 high-resolution compositions check the actual border compositor and preview, not the complete application export encoder.

尚未真机验证，不包含正在独立开发的 GPU 加速。原生引擎和编解码器不变。字重调整是应用视觉修订，不代表工厂字样精密测量。其余片边证据和限制见 [120 画幅与素材说明](MEDIUM_FORMAT.md)。

Physical-device validation remains outstanding. GPU acceleration is not included, and native engine/codecs are unchanged. The lettering adjustment is authored visual styling, not a factory measurement.
