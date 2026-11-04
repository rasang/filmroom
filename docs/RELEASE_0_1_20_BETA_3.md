# Filmroom 0.1.20-beta.3

Development pre-release from `dev`; the latest stable release remains 0.1.19. Android package `dev.filmroom`, version code 31. The ARM64 APK uses the existing release certificate and can update the prior fixed-signature release without uninstalling.

## 修复 / Changes

- 135、645、6×6、6×7、6×9、6×17 画幅选择直接放入「构图」，与横竖方向和裁切一起取消、确认、撤销或重做。
- 移除胶卷选择区的画幅入口和界面里的 120 参考说明。源码保留各款证据、生成脚本和限制说明。
- 补齐 Portra 400、Gold 200、Ektar 100、E100 的已核实连续片边，避免 6×17 使用局部旧素材时露出无证据空段；按各款坐标模型排布底部箭头和数字。
- 按各自实片参考调扁 Portra 160、400、800 的箭头，保持数字、位置和上轨不变。修正 Fuji 原厂图中已明确的局部标记；不把不同批次版式拼成一卷。
- 精确按胶卷与素材版本读取、缓存片边。保留全部旧素材和中间版本，已排队任务不会换成新片边。Portra 800 的 +1/+2 仍共享同一物理胶卷素材。
- 保持无边框裁切、135 画幅和原有黑白边框行为。

Frame choices now live directly in Composition and share its cancel/confirm/undo transaction. The film-selector reference notice is removed from the editor, while source evidence remains documented. Reconstructed continuous spans, stock-specific lower-rail coordinates and flatter Portra arrows address the reviewed edge issues. Exact-version resolution preserves historical queued output and processing aliases.

## 验证与边界 / Validation and limits

针对性主机检查覆盖构图交互、取消/返回、快速切换、横竖方向和历史恢复；120 当前素材覆盖 190 种胶卷/路线/画幅/方向组合，预览与 float 合成的 sRGB8 结果逐像素对照。审阅成品另外核对箭头范围之外的像素保持不变。完整主机测试、lint、签名、APK 资源及哈希结果随 GitHub 发布记录提供。

Focused host checks cover composition transactions, interrupted/rapid interactions, orientation and history. The current 120 artwork is checked across 190 supported stock/route/format/orientation combinations. Preview is compared pixel-for-pixel with the float compositor after sRGB8 quantization. High-resolution review PNGs exercise the border compositor, not the complete application export encoder. Final suite, lint, signing and APK integrity results are recorded with the published release.

尚未完成 Android 真机验证。本版不加入 GPU 加速，不改变原生引擎、编解码器、颗粒、光晕或科学胶片宽度参数。片边仍是参考重建；字体、绝对节距、两侧相位和颜色不是工厂实测值。Provia 使用已审阅的原厂双边示意版式，实际批次可能不同。详见 [120 画幅与素材说明](MEDIUM_FORMAT.md)。

Physical-device validation remains outstanding. GPU acceleration is not included; the native engine and codecs are unchanged from beta.2. The edges remain authored reference reconstructions with approximate typography, metric pitch, opposite-edge phase and color. Provia follows the reviewed manufacturer-diagram variant, not every physical batch.
