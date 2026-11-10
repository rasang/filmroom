# Live Photo 本机准入条件

来源为 DJI Mimo 2.12.8 APK 的静态恢复代码，SHA-256
`33aee14572993b62146e9daea225ae4a0ab7d36fbfb7878dcb08d14cf8dd111c`。
这些是入口条件，不是 Filmroom 导出成品的相册验收结果。

- vivo：`support=1` 支持线索；`2` 阻断；其他查 SDK 36 项型号表。
  未命中为 UNKNOWN 可尝试，此处有意不同于 Mimo 的 false。
  `version` 仍独立决定单双文件，不用于证明设备支持。
- OPPO / ONEPLUS / REALME：manufacturer 忽略大小写精确匹配，
  `PackageManager.hasSystemFeature("oplus.software.gallery.olive")`。
  正常查询 false 阻断，true 准入；查询异常为 UNKNOWN 可尝试。
  没有额外 Android 14 门槛。
- 荣耀：`manufacturer == "HONOR"`（区分大小写）且 SDK >= 34。
  Mimo 的入口调用 `LivePhotoUtils.isSupportLivePhotoPlay()`，该 SDK 方法如此判断。
- 三星：manufacturer 忽略大小写等于 samsung 且 SDK >= 34。
  Mimo 使用 OLive，Filmroom 仍输出原有 SEF 107/106，不能据此宣称 SEF 已验收。

本机条件只在有本机 environment 时应用；显式远端目标不借用本机系统条件。
准入后 profile 状态仍是 UNKNOWN，除非存在同一环境下的明确相册验收记录。
不满足准入时，手动格式选择和 direct attempt 均不能绕过。

UI：明确阻断时 Live 按钮去主题色、降低透明度但保留点击；点击弹 Toast，
不启动编码或保存。未知状态仍可点击尝试。不会自动替用户导出视频。

恢复证据定位：
- `ManufacturerUtils.a/e/f`：荣耀 / OPPO 系 / 三星识别。
- `CaptureLivePhotoManager.o(Context)`：各厂商入口与三星 SDK_INT 门槛。
- `com.hihonor.mcs.media.datacenter.livephoto.LivePhotoUtils.isSupportLivePhotoPlay`：荣耀门槛。
- `AndroidOliveDecode.Companion.isDeviceSupportOlive`：OPPO 系系统 feature。

验证范围：JVM / Robolectric 条件与按钮测试、Debug 编译；不替代真机属性读取、
系统 feature 返回值和相册识别播放验收。

最终校验（2026-11-10）：42 项 LivePhoto 定向测试及 126 项配方兼容 gate 全部通过，
ARM64 Debug APK、lintAnalyzeDebug 与 diff 空白检查通过。完整 lintDebug 因离线缓存缺
instrumentation runner/rules/ext:junit 依赖未跑通；无真机验证。首次重跑遇到 /tmp
空间耗尽，改用 workspace 临时目录后完整命令成功，未删除研究材料。
