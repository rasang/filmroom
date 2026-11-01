# 暗房 Android 集成独立复审

状态：源码及最终 APK 复审完成；本范围未发现仍未解决的 P1/P2。仅静态审查、APK 内容核对与主机构建/测试证据，不是安卓真机或模拟器测试。

## 已核实的改动与边界
- Manifest 没有 INTERNET、相册广泛访问、存储、相机权限；云备份和设备迁移均排除应用数据。诊断 FileProvider 仅暴露 diagnostic-shares。
- RAW 导入在进入 JNI 前记录新输出路径，取消和未交付结果删除新原件副本及新 RAW spool；恢复已存在 RAW 为借用，失败/取消不删除。原尺寸 RAW 导出借用源 spool，标准尺寸使用独立浮点缩放 spool。
- LinearSpool 按两行缓存读取和插值，保留 float 精度与范围，不通过 8 位 Bitmap 导出 RAW。
- PhotoCodecs 每次创建新 native handle；取消 watcher 在 worker dispatch 前安装，native worker 返回后才 join watcher 并 destroy。需结合最终 JNI 测试验证。
- TIFF 输出使用 MediaStore.Downloads，提示“下载/暗房”；完成前 IS_PENDING，清理限定应用 owner 和 pending。原片不覆盖。
- 导出期间亮屏，非配置变更的 onStop 取消，配置变更保留 ViewModel；分享诊断日志前明确提示会取消正在进行的导出。
- 全尺寸处理和格式兼容仍需真机验证，不能用 Robolectric BitmapRegionDecoder 的空白 shadow 代替。

## 发现与处理
1. P2：已有 RAW spool 恢复时 nativeProbe 仍要求另一份输出空间，低磁盘会无故拒绝恢复。已修复并核实：仅 probe 接受 metadataOnly:true 并绕过新输出准入；decode/encode 不可据此跳过预算。native host JNI 用极小内存/磁盘预算验证 probe；app retained ownership 测试确认使用此模式。
2. 撤回：曾以 CLI benchmark 的 nested probe JSON 推断 JNI 不匹配。直接阅读 jni_bridge.rs 后确认 JNI 本来是顶层 width/height；不是缺陷，不应修改现有解析。已向父任务与实现者明确更正。

## 最终验收结果
- 独立读取最终 XML：89 tests，0 failures/errors/skips。包括高像素真实 JPEG/PNG 导入、显式 fake JNI RAW 所有权/取消、真实 Linux JNI 快照合同、浮点缩放精度、APNG 拒绝、fake MediaStore TIFF Downloads 提交与日志清理。测试边界在各文件明确标注。
- 最终 APK：暗房-0.1.0-本地版.apk，20,274,056 bytes，SHA-256 `0ed757eaa1436ce7dafa8c9f3a7dc1f20550bdcf6e2eced6e2ae95e8278293f0`。
- 从 APK ZIP 实际读取四个 native library，均精确匹配 V5 engine manifest 与最终 codec manifest。四者无压缩、ZIP 数据偏移均 16 KiB 对齐；无额外 native library。
- 通过 aapt 独立检查最终合并 Manifest：无网络/存储/广泛媒体权限；唯一 uses-permission 是 AndroidX 自定义签名级动态接收器权限。allowBackup=false；FileProvider 不导出，只准诊断目录。非 debug release。
- APK 未见测试照片、RAW/float 中间文件或密钥文件。assets 除胶片数据外只有基线 profile 与四份许可证；codec notices 精确匹配 codec manifest。
- 原生 V3 签名验证记录为 Verifies；设备最低 API29 支持 V3。完整证书为项目自签名，不是用户私钥。
- 复核 RAW 可选镜头校正提示进入导入/恢复消息，TIFF UI 明确“下载/暗房”与相册兼容限制，中文许可索引涵盖 codec LGPL 与依赖。
- 最终源文件相对 18:02 独立快照无变化；详见 `android-final-review-evidence.json` 与 `android-final-apk-independent-check.json`。

## 不能据此声称
- 未运行安卓真机或模拟器，未验证系统真实 MediaStore / 长时间运行 / 实机速度与内存；不把 fake provider 或 Robolectric region shadow 当作运行验收。
- 此复审不是所有 RAW、压缩 DNG、HEIF 或 TIFF 导入全面支持证明。以实际界面与说明列出的格式边界为准。
