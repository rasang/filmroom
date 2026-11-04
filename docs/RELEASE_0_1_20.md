# Filmroom 0.1.20

Formal release of the functionality shipped in 0.1.20-beta.4. Android package `dev.filmroom`, version code 33. The release branch is `main`; the existing `dev` history is preserved. Compared with beta.4, only version metadata and release documentation change.

## 更新 / Changes since 0.1.19

- 支持受限的 16 位三通道 LinearRaw 条带 JPEG XL DNG，不代表所有 DNG 或所有机型均受支持；格式兼容与编解码边界见 [JPEG XL DNG 说明](JPEG_XL_DNG.md)。
- 加入 6×4.5、6×6、6×7、6×9、6×17 五种中画幅，以及九款实际存在的 120 胶卷对应素材。135 与无边框构图继续保留。中画幅仅改变裁剪与片边表现，不改变科学引擎的 filmFormatMm 或光学计算。
- 画幅选择放入既有构图栏，横竖切换采用固定在 135 前的单个叠框按钮，不随画幅列表滚动。保留方形明确方向、取消、确认与撤销行为。
- 竖屏取消/确认的视觉背景稍矮，文字、宽度和至少 48dp 的触控范围保持。窄屏大字时标题与动作自动分行。
- 修正逐款片边编号、箭头和底轨间距；Portra 160/400/800 箭头形状及 Portra 400 品牌文字重量按审阅结果调整。保留全部 25 个历史与当前版本，旧队列按精确版本读取素材。

Adds bounded 16-bit, three-channel LinearRaw strip JPEG XL DNG input (not universal DNG or device support), five medium-format compositions and artwork for nine physical 120 film stocks. Composition uses a fixed orientation toggle before the scrolling format choices, responsive actions and slightly shorter portrait action pills. Medium-format presentation changes crop and border appearance, not the engine's scientific filmFormatMm or optical calculations. Reviewed edge markings and lettering retain exact-version compatibility for queued exports.

## 升级与验证 / Upgrade and validation

发行包为 ARM64，适用于 Android 10（API 29）及以上设备。使用与 0.1.17 起固定发行版及本轮 beta 相同的发行证书，versionCode 33 高于 beta.4 的 32；签名与版本允许覆盖升级，但尚未进行真机安装升级验证。较早旧签名包的迁移要求仍见 [0.1.17 说明](RELEASE_0_1_17.md)。

This ARM64 release retains the existing release certificate. Version code 33 is newer than beta.4's 32. Signature and version checks support in-place upgrades from matching fixed-signature releases, but physical-device installation and upgrade have not been tested.

完整主机测试、lint、源码与产物哈希、APK 签名和资源核验结果随 GitHub 发行记录提供。主机检查包括构图交互与大字布局，以及 190 个有效片边组合的预览/float 合成 sRGB8 像素一致性；这些检查不替代完整应用导出与真机验收。

Full host-test, lint, source/artifact-hash, APK-signature and resource-verification results are provided in the GitHub release record. Coverage includes composition interactions, large-text layouts and preview/float sRGB8 parity across 190 supported border combinations. These are not a substitute for end-to-end physical-device validation.

## 已知边界 / Known limits

- 尚未完成 Android 真机安装升级、完整导入到导出、视觉、速度、内存、耗电与发热验证，不作真机性能保证。
- 本次未重跑完整原生测试或真实 RAW 压力矩阵；沿用已验证的 beta.4 原生二进制及既有专项证据。
- 不含独立开发中的 GPU 加速；原生引擎及编解码二进制与 beta.4 完全相同。
- 片边为应用生成的视觉素材，并非原片扫描或精密工厂复刻。部分款式使用官方示意，实片批次差异和证据范围保留在 [120 素材说明](MEDIUM_FORMAT.md)。Portra 400 字重属于应用视觉调整。
- 源码不提交预构建 JNI 库，按 [开发指南](DEVELOPMENT.md)及 JPEG XL DNG 说明重建。

Physical-device and full import-to-export validation remain outstanding. No phone performance, memory, battery or thermal claims are made. The complete native suite and real-RAW stress matrix were not rerun for this promotion; previously verified beta.4 native binaries and focused evidence are retained. GPU acceleration is excluded. Artwork is authored styling, with documented evidence limits and batch differences, not an exact factory reproduction. Prebuilt JNI libraries are omitted from the source repository.
