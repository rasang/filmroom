# 导出列表 · 照片优先

此改动是 0.1.4 冻结之后的界面源码修订，不改版本、签名、导出算法或服务队列。

- 当前处理、暂停或失败保留大图：竖屏固定 176 dp，宽屏 224 × 180 dp 与信息并排。等待、完成和取消采用 104 × 112 dp 紧凑照片行；双倍字体改为全宽文字，点详情可展开照片。状态切换按可见卡片锚点保持位置。缩略图采用中心裁切，仅用于识别照片，不表示实际导出裁切。按用户要求不显示“原图参考 / 负片示意 / 导出成片”等图片角标；真实图片来源仍按文件选择，无障碍描述保留真实语义，不能把原图声称为成片。缺失时仅有占位图标。
- 页头仅显示实际处理/等待/暂停数量；状态与实际胶片名为次级信息。完成显示“已保存”，取消显示“已取消”，阶段细节折叠；失败明确突出“导出失败”和原因。当前阶段、`第 n/8 步 · 本阶段百分比`始终显示，不需打开详情。8 段细轨代表阶段而非耗时；只填充当前阶段已报告的工作量。未知工作量为静态轮廓，不虚构百分比或循环动画。
- 详情、取消为低干扰文字按钮；仅继续 / 重试 / 查看照片用实心主按钮。所有操作仍有至少 48 dp 点击目标；取消保留二次确认，已保存照片不显示取消。
- 进度更新复用原 View，不重建列表。缩略图固定槽、滚动锚点和已展开详情保留；照片离开邻近视口释放引用，停止页面和移除卡片时取消视觉工作。
- 同阶段真实数值更新最多 180 ms 插值；阶段改变直接定位。系统禁用动画、窗口隐藏、页面停止、视口外均不持续动画。暂停同一数值也会取消动画。
- 失败原因保持可见；详情显示尺寸、胶片、色域及恢复说明。不展示文件路径、原文件名、URI 或 EXIF。

## 验证

`ExportQueueActivityHostTest` 实际 Android View 的 Robolectric native-graphics 渲染：320×640 / 360×780 / 740×320，1×与2×字体；另有等待、执行、暂停、失败、完成、取消状态图。检查不省略文本、按钮尺寸、横屏阶段可见、详情切换与行复用、滚动/旋转锚点、通知定位。`ExportStageRailHostTest` 覆盖暂停、视口外、停止及系统禁用动画。

图像位于 `app/build/ui-host-renders/queue/`。测试预览来自仓库公开 CC0 Pixel 2 XL DNG 的简化参考图，不是用户照片，也不是处理引擎成片；渲染不叠加图片标签；无障碍语义仍为原图参考。测试资源不进入 APK。阶段选择的原图/负片/成片真实性由 `ExportQueuePresentationTest` 独立验证。

这是 host UI 验证；未声称安卓真机/模拟器已经通过。

### Unviewed completed exports

- The editor queue entry keeps its pending/running count; a separate 4dp dot indicates at least one `COMPLETED` record with `completionViewed=false`. Failed/cancelled tasks never light the dot.
- `completionViewed` is optional version-1 manifest metadata (legacy records default to unread), not checkpoint/engine state. Acknowledgments preserve job order, createdAt, progress and parameters. No global “last opened” timestamp can consume a concurrent completion.
- Only a foreground/resumed, focused queue draw containing meaningful completed-status text (at least 16dp visible, clipped to the actual ScrollView viewport) schedules an acknowledgment. Creation, layout, thumbnail prefetch, background frames, running snapshots and offscreen cards cannot mark results read. Restoring scroll/orientation does not consume other cards.
- The store serializes acknowledgment against worker updates; stale completed worker copies preserve durable reads. Each captured displayed snapshot is rechecked against current completed identity before persistence. Disk errors leave the affected dot on without Toast spam or stopping export. Other visible records still acknowledge independently; an idle foreground screen retries via 2–30 second backoff instead of waiting for another progress event.
- Read-only emissions bypass list layout/anchor restoration; card identity and scroll remain unchanged. No new notification permission request or export-service dependency is introduced.
