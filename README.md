<div align="center">

# Filmroom

**把照片带进胶片暗房。**

原生 Android 胶片模拟 · 本机离线处理 · 无账号、无广告

**简体中文** · [English](README.en.md)

[界面与样片](#界面与样片) · [安装与版本](#安装与版本) · [开始使用](#开始使用) · [配方与照片动效](#配方与照片动效) · [格式支持](#格式支持) · [开发指南](docs/DEVELOPMENT.md)

<img src="app/src/main/assets/film-rolls/kodak_portra_400.webp" width="100" alt="Portra 400 原创胶卷插画" />
<img src="app/src/main/assets/film-rolls/fujifilm_c200.webp" width="100" alt="Fujifilm C200 原创胶卷插画" />
<img src="app/src/main/assets/film-rolls/kodak_gold_200.webp" width="100" alt="Kodak Gold 200 原创胶卷插画" />
<img src="app/src/main/assets/film-rolls/fujifilm_provia_100f.webp" width="100" alt="Provia 100F 原创胶卷插画" />
<img src="app/src/main/assets/film-rolls/kodak_vision3_500t.webp" width="100" alt="Vision3 500T 原创胶卷插画" />
<img src="app/src/main/assets/film-rolls/kodak_ektar_100.webp" width="100" alt="Ektar 100 原创胶卷插画" />

<sub>应用内的原创胶卷选择素材；不是实物照片或成像效果对比。[素材来源与品牌说明](app/src/main/assets/film-rolls/ASSET_PROVENANCE.md)</sub>

</div>

## 界面与样片

### 应用界面

照片编辑与后台导出队列。截图展示实际使用界面，细节可能随版本更新。

<p align="center">
  <a href="docs/images/showcase/editor.webp"><img src="docs/images/showcase/editor.webp" width="36%" align="top" alt="照片编辑" /></a>
  <a href="docs/images/showcase/export-queue.webp"><img src="docs/images/showcase/export-queue.webp" width="36%" align="top" alt="后台导出队列" /></a>
</p>

### 无边框样片

保留照片本身的画幅，让色彩、颗粒和光影成为主角。

<p align="center">
  <a href="docs/images/showcase/unframed-sunset.webp"><img src="docs/images/showcase/unframed-sunset.webp" width="31%" align="top" alt="车窗外的落日" /></a>
  <a href="docs/images/showcase/unframed-city.webp"><img src="docs/images/showcase/unframed-city.webp" width="31%" align="top" alt="暮色中的城市道路" /></a>
  <a href="docs/images/showcase/unframed-station.webp"><img src="docs/images/showcase/unframed-station.webp" width="31%" align="top" alt="车站里的光影" /></a>
</p>
<p align="center">
  <a href="docs/images/showcase/unframed-blossoms.webp"><img src="docs/images/showcase/unframed-blossoms.webp" width="31%" align="top" alt="日光下的花枝" /></a>
  <a href="docs/images/showcase/unframed-sign.webp"><img src="docs/images/showcase/unframed-sign.webp" width="31%" align="top" alt="树荫间的标牌" /></a>
</p>
<p align="center">
  <a href="docs/images/showcase/unframed-escalator.webp"><img src="docs/images/showcase/unframed-escalator.webp" width="64%" align="top" alt="灯光下的扶梯" /></a>
</p>

### 齿边水印样片

横幅与竖幅分别展示，保留完整齿孔、边框和画面。

<p align="center">
  <a href="docs/images/showcase/framed-scooters.webp"><img src="docs/images/showcase/framed-scooters.webp" width="31%" align="top" alt="齿边样片：路边的电动车" /></a>
  <a href="docs/images/showcase/framed-interior.webp"><img src="docs/images/showcase/framed-interior.webp" width="31%" align="top" alt="齿边样片：室内光影" /></a>
  <a href="docs/images/showcase/framed-garden.webp"><img src="docs/images/showcase/framed-garden.webp" width="31%" align="top" alt="齿边样片：树下的园景" /></a>
</p>
<p align="center">
  <a href="docs/images/showcase/framed-dog.webp"><img src="docs/images/showcase/framed-dog.webp" width="31%" align="top" alt="齿边样片：小狗" /></a>
  <a href="docs/images/showcase/framed-cat.webp"><img src="docs/images/showcase/framed-cat.webp" width="31%" align="top" alt="齿边样片：猫" /></a>
</p>

<sub>以上为用户提供的界面截图与成片。点击图片查看大图；网页展示副本已缩小并移除元信息，未额外调色；样片保留完整画幅，界面截图仅整理顶部留白。齿边为应用生成的边框水印，并非实物胶片扫描。</sub>

## 从选一卷胶片开始

Filmroom 将胶片、相纸与扫描路线放进手机上的同一间暗房：选一卷胶片，调整曝光、颗粒与光晕，再把成片保存为新文件。照片始终在本机处理，原文件不会被覆盖。

- **彩色与黑白胶片、相纸** — 包含彩色负片、反转片、推片与黑白配置；新增 CineStill 800T、Tri-X 400、T-Max 100、Acros II、HP5 Plus 和 Ilford Multigrade IV RC。胶片与相纸可分别选「无」，胶卷与相纸包装选择器方便浏览。
- **不止选一个滤镜** — 胶片曝光、印相或底片直扫、颗粒、光晕与显影控制，搭配原片对比、撤销和重做。
- **构图与边框** — 自由或比例裁剪、构图锁定、±15° 调平（0.1° 步进），以及 135/120 胶片边框、日期与曝光参数印字。支持将片边与印字放在成像前一起处理；亮度直方图针对预览照片区域，不包含边框与印字，也不是原图全分辨率直方图。
- **导出留给后台** — 队列保存照片与参数快照，显示处理进度；支持阶段检查点和中断后手动继续。系统仍可能停止后台任务。
- **按用途保存** — JPEG、PNG 或 16 位 TIFF；可选 sRGB、Display P3、Adobe RGB (1998) SDR 输出。
- **保存配方与分享** — 命名保存编辑参数，扫码或从相册识别配方二维码，确认后保存到配方列表。
- **让成片动起来** — 观片台、取景器两种照片动效，可预览并导出视频或 Live 照片。
- **用熟悉的语言编辑** — 中文、English、日本語、한국어，可在设置中切换。

## 安装与版本

**本文对应 `dev` 源码 0.1.22-beta22（versionCode 74，`0762a9f`）的功能范围。** 开发分支与正式发行包不一定同步；可下载版本以 [GitHub Releases](https://github.com/rasang/filmroom/releases) 为准，也可按[开发指南](docs/DEVELOPMENT.md)自行构建。尚未合入此分支的候选改动不包含在本文范围内。

- 面向 **Android 10（API 29）及以上的 64 位设备**；源码默认构建 ARM64 和 x86_64，可单独构建 ARM64 手机包。
- 项目仍处于早期体验阶段。已有主机构建、算法及 JNI/UI 测试，**尚未完成 Android 真机全流程验证**，不作手机速度或耗电保证。
- 0.1.17 改用固定发行签名；与 0.1.16 及更早体验包不同，首次须卸载旧版再安装，应用内参数、临时照片和队列会清除，已保存相册成片不受影响。之后同签名版本可覆盖升级。自行构建需使用自己的密钥；Release 构建默认未签名。详见[迁移与更新说明](docs/RELEASE_0_1_17.md)。

历史 JPEG XL DNG、中画幅与构图改进见 [0.1.20 更新说明](docs/RELEASE_0_1_20.md)。此前 RAW、HEIC、黑白胶片、局部预览与导出管理改进见 [0.1.19 更新说明](docs/RELEASE_0_1_19.md)。[0.1.18 更新说明](docs/RELEASE_0_1_18.md)保留此前界面整理记录。

## 开始使用

1. **导入照片** — 从系统「相册」或「文件」选择一张照片。RAW 请从文件入口选择；兼容范围见下表。
2. **选择胶片与调整** — 点选胶卷，调整曝光、颗粒和光晕；按需切换印相/直扫路线、裁剪或添加边框，用「对比」查看原片。
3. **导出新文件** — 选择格式、尺寸与色域，加入导出队列。JPEG/PNG 保存到相册「暗房」，TIFF 保存到「下载/暗房」。

> **批量导出与发热：** 持续处理多张照片会让手机保持较高负载，可能明显发热、增加耗电，并因系统温控降频而变慢。建议分批导出；发热明显时暂停处理，让机身降温后再继续。

完整参数说明见[使用说明](使用说明.txt)。导出会保留支持的拍摄元信息，**包括原有 GPS 和作者信息**；公开分享前请留意，详见[元数据规则](docs/EXPORT_METADATA.md)。

## 配方与照片动效

### 保存自己的暗房配方

保存、命名、加载或删除配方，也可生成分享图和二维码，通过相机扫码或相册图片导入。导入先确认并保存，不会直接覆盖当前照片的编辑。

配方包含胶片、相纸、调色、颗粒、构图/调平、边框和日期/曝光印字等编辑参数，**不包含照片本身**。将配方应用到另一张照片时也会带入已保存的构图和手动日期，请按需调整。详见[配方说明](docs/SAVED_PRESETS.md)与[二维码分享](docs/PRESET_QR_SHARING.md)。

### 观片台与取景器

| 观片台：灯光亮起 | 取景器：对焦、快门与成片 |
| --- | --- |
| [![观片台动效演示](docs/assets/effects/light-table.gif)](docs/assets/effects/light-table.mp4) | [![取景器动效演示](docs/assets/effects/viewfinder.gif)](docs/assets/effects/viewfinder.mp4) |

[观片台 MP4](docs/assets/effects/light-table.mp4) · [取景器 MP4](docs/assets/effects/viewfinder.mp4)

*无声、3 秒循环预览。使用本分支真实动效 shader、纹理与时间线离屏渲染，输入为自制示例插画；不是手机录屏，也不代表实机性能。GIF 用于 README 直接展示，点击可打开 30 fps MP4。[素材与生成说明](docs/assets/effects/README.md)。*

在导出记录中打开照片动效，每段约 3 秒：

- **观片台**：模拟灯光亮起与胶片观看；左右换图时观片台保持固定、胶片滑动，离开照明区域的部分变暗。
- **取景器**：模拟 Nikon F3 风格的快门/光圈读数、由模糊到清晰的裂像对焦和反光板遮挡。竖构图参数位于右侧，裂像与反光板方向相应旋转。
- **浏览与重播**：首次进入自动播放当前照片一次；左右滑动切换、滑回第一张都不自动播放，点「重播」才再次播放。支持距离或快速划动切页、相邻照片预加载和有界缓存；低内存或离开页面后仍可能重新加载，不保证零延迟。
- **原片对照**：使用该次导出记录保存的构图/调平信息，不把未裁剪原片当作编辑后的原片。旧记录缺少信息时不能保证还原同一构图。
- **声音与导出**：预览及视频/Live 使用对焦提示、机械快门或观片台开关音，并混入带头尾渐变的轻微底噪。可选原尺寸、长边 1920 或 1080；编码能力或资源不足时可能降级，见[导出诊断](docs/EFFECT_EXPORT_DIAGNOSTICS.md)。

**Live 边界：** 这里指把动效封装为 Live 照片输出，不代表已支持读取各品牌 Live 原图里的视频；现有照片导入仍读取静态画面。系统相册是否识别、能否播放声音及不同设备的编码能力需要实机验证，不保证所有品牌/相册兼容。

## 备份、删除与更新

- **本地备份**：在设置中导出/恢复 ZIP，包含受支持的设置、配方、导出记录及照片访问链接，**不打包原照片或成片文件**。恢复需要确认；重装后可能需要重新授权或关联照片，不等于恢复照片文件或运行中的导出任务。备份未加密，含记录及访问链接，请妥善保管。
- **删除确认**：如果已经记住删除范围，删除前仍会显示确认并说明范围；未记住时继续使用原来的范围选择弹窗，不再重复弹一次。
- **检查更新**：设置中可手动检查，冷启动也会限频检查。更新窗口展示版本说明和下载入口，由浏览器打开下载页面，不会自动下载或安装 APK。

## 格式支持

| 输入 | 当前范围 |
| --- | --- |
| JPEG / PNG | 系统解码；PNG 透明区域合成到白色背景。 |
| HEIC | 受限的普通静态 8 位 HEVC 主图/网格图；依赖设备解码器及足够内存。支持范围内的 P3 转为 sRGB SDR，不是 HDR 编辑。 |
| NEF / CR2 / ARW | 已实现普通 Bayer、Canon mRAW/sRAW、Nikon HE/HE* 与 small NEF、Sony 小尺寸/ARW6 等已验证分支；读取真实像素，不用嵌入预览替代。具体编码与资源限制见下文。 |
| Canon CR3 | 已接入受限的单张 RAW / C-RAW 传感器解码，不用嵌入 JPEG 预览替代；不支持或未验证的连拍、多样本等布局仍会拒绝。 |
| Olympus / OM System ORF / ORI | 按文件内容识别并解码支持的 Bayer 传感器布局；ORI 走同一 ORF 解码路径，不保证所有高分辨率模式，80 MP 文件仍超出当前导入上限。 |
| Pentax PEF | 已接入受限的 Bayer RAW 解码；像素位移、HDR 合成及单色等不支持或未验证变体仍会拒绝。 |
| RAF | 富士 Bayer 与 X-Trans 的受限基础显色候选；已测未压缩、无损/有损压缩样片，保留原始像素与 EXIF。具体机型、模式、GFX/旧式布局限制及验证状态见 [RAF 范围](docs/FUJIFILM_RAF.md)。 |
| DNG | 新增受限 16 位三通道 LinearRaw JPEG XL 条带导入，见[JXL 范围](docs/JPEG_XL_DNG.md)； 既有 16 位路径，以及新增已验证的 12/14 位三通道 LinearRaw SOF3 条带路径；S23 系列 4 份公开样本通过主机整幅像素/线性色彩对照，其中仅 1 份明确为 Expert RAW。不是全品牌/全模式保证。 |
| Panasonic RW2 | 13 份样本验证 v4、v5（14 位）、v6、v7（14 位）及受约束的 v8/CF2；包括 GH6、S5 II X、G9 II、GH7、S1R II 的已测模式。特殊编码与超限文件仍可能被拒绝，详见[边界](codecs/README.md)。 |

| 输出 | 适合用途 |
| --- | --- |
| JPEG · 8 位 | 通用分享，质量 1–100，默认 95。 |
| PNG · 8 位 | 避免再次 JPEG 压缩；不恢复输入中不存在的精度。 |
| TIFF · 16 位 | 继续后期，文件较大；部分相册无法显示。8 位输入不会因此变成 RAW。 |
| MP4 / Live 照片 | 从照片动效页面导出；设备编码、相册识别与音频播放限制见上文。 |

**使用前请知道：** DNG 后缀不代表一定兼容；HEIC 导出、其他未列出的相机 RAW、AVIF、EXR 和 HDR 增强/保存尚未接入。RAW/HEIC 导入上限为 5200 万像素，最终编码上限为 8400 万像素，仍受格式、内存与空间限制。局部放大预览最长边仍为 1536 像素，**不是原图像素级预览**。不支持的文件变体或内存/临时空间不足时会明确停止，不暗中缩图完成原尺寸导出。

**相机 RAW 验证边界：** 上述列表说明已验证的格式/像素布局，不是机型白名单；按文件内容和离线色彩配置识别，不仅凭后缀放行。没有承诺所有未知新机型、未来压缩变体或任意损坏文件均兼容。基础矩阵显色不复刻相机风格；small NEF 使用独立验证的已平衡线性 sRGB 路径，避免重复白平衡。开发验证中的 host 结果不等于 Android 真机结果。

详细范围：[HEIC](docs/HEIC_IMPORT.md) · [DNG 与编解码器](codecs/README.md) · [小米 DNG 适配边界](docs/XIAOMI_DNG_COMPAT.md) · [预览](docs/LOCAL_DETAIL_UI.md)。

## 照片只留在你的设备上

照片处理在本机完成，没有账号或自动上传照片的功能。应用具有网络权限，用于手动或冷启动限频检查更新；相机权限用于主动扫码，恢复照片访问时可能请求相册权限。一般导入通过系统选择器授权，临时文件保存在应用私有目录。系统自动应用备份关闭；设置中的手动 ZIP 备份由你主动导出。

遇到问题，可在「诊断日志」中手动分享脱敏 TXT，再到 [Issues](https://github.com/rasang/filmroom/issues) 描述版本、设备和复现步骤。日志不包含照片、文件名、GPS 或路径；分享动作由你发起，不会自动上传。

## 开发与文档

原生 Kotlin Android 界面，Rust/JNI 图像引擎与编解码组件。图像处理离线运行；检查更新及首次构建依赖解析可能需要联网。

- [开发指南](docs/DEVELOPMENT.md)：环境准备、构建、原生组件替换与验证范围。
- [动效音频准备](docs/EFFECT_AUDIO_SETUP.md)：公开仓库不包含第三方实录混音的独立音轨，需按来源与许可自行取得录音并生成；仅克隆源码不能保证与发行包声音一致。原生 JNI 库也需按开发指南准备。
- [动效切页与缓存](docs/EFFECT_PAGE_CACHE_BETA22.md)：缓存、快速划动与快门数字间距的实现及验证边界。
- [历史开发记录](docs/HISTORY.md)：截至 0.1.16 的完整旧 README，保留版本变更和验证限制。
- [验证记录](验证记录.md)：已执行的测试及未覆盖的设备流程。

## 开源来源与许可

Filmroom 的胶片与相纸引擎基于 Andrea Volpato 的 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 及 [spektrafilm-rs](https://github.com/turbasvin/spektrafilm-rs) 的 Rust 移植工作。

程序代码遵循 [GPL-3.0](LICENSE)；相关胶片档案与 LUT 数据遵循 **CC BY-SA 4.0**，见[上游许可与数据声明](app/src/main/assets/licenses/SPEKTRAFILM_LICENSE.txt)。其他组件保留各自的[许可和声明](app/src/main/assets/licenses)，恢复预设图标来自 [Material Symbols](third-party/material-symbols-reset-settings/NOTICE.txt)。

胶卷选择插画为项目原创素材，边框包含参考重绘与模型化表现，并非原始胶片扫描；[素材说明](docs/FILM_STOCK_ARTWORK.md)保留来源与限制。Kodak、Fujifilm 及胶片名称归各权利人所有，仅用于识别配置，不表示合作或背书。


