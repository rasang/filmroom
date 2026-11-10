# vivo 2014 双文件候选

基于正式 dev `0278d7a` 的独立实现，不含首页实验代码、不包含 DJI/vivo SDK 二进制或反编译源码。

## 依据与范围

静态核对官方 DJI Mimo Android 2.12.8 内含 vivo SDK 2.4.2 的 V2 → V1 路由及 V1 输出协议。APK SHA-256：`33aee14572993b62146e9daea225ae4a0ab7d36fbfb7878dcb08d14cf8dd111c`。
这是特定 SDK 版本的行为依据，不是 vivo 全机型兼容认证。

本地 vivo/iQOO 读取 `ro.vivo.camera.livephoto.version`：

- 整数 >= 1：优先既有 `VIVO_SINGLE`。
- 整数 < 1：优先新增 `VIVO_PAIR_2014`。
- 空值、读取异常、无法解析：兼容 SDK 的默认值 0，优先 2014，但分别保留 MISSING、UNREADABLE、INVALID，绝不称实际属性值为 0。
- 普通应用反射读取可能受限制；ADB 可读不代表应用可读。无需新增存储权限、执行 shell 或绕过 hidden API 限制。
- 仅本机品牌为 vivo/iQOO 且未指定远端 targetBrand 时读取。远端目标没有属性观察，保持既有默认次序，新增 2014 供手动选择。
- 手动 profile 与有效的用户相册验收记录仍优先。属性只能决定候选顺序，不能写入 SUPPORTED 记录。
- 支持能力与封装版本是两件事；新增 support 判断见下文。

## 文件协议

同 basename 的 `.jpeg` + `.mp4`，相同的 28 位 ASCII 十进制随机 ID。使用密码学随机数避免毫秒时间戳并发碰撞；不复制 SDK 的时间戳补零算法。

两边都只写：

```json
{"com.android.camera.livephoto":"<28 digit ID>","version":2014}
```

不编造 `imageTime`、`newCoverTime`、来源或旋转标记。

JPEG 尾部：`vivo` + UTF-8 JSON + 4 字节大端 JSON 长度 + `cameralbum!` + 47 字节配对记录。
配对记录：大端 47 + 28 字节 ID + 4 个 FF + 固定 11 字节签名。
MP4 尾部：标准 32 位大小 `uuid` box，16 字节 user type `vivoMediaExtInfo`，内容为上述尾部；box 大小是 24 + 尾部字节数。

已识别且通过解析的旧 JPEG 尾部、末尾 MP4 vivo UUID 在重封时移除，避免重复叠加。非末尾 vivo UUID 会拒绝，避免删除后破坏 chunk offset；未知尾部/增益图不静默丢弃。旧字段不继承：此 API 面向 Filmroom 已归一化视频与 SDR 成片，不是任意原始 Live 的无损元数据编辑器。视频媒体前缀逐字节复制并校验 SHA-256。

## 与 Mimo 有意保留的差异

- 既有 EXIF 清理/拍摄日期恢复策略继续使用，非原文件全量拷贝。
- 不保留原 Live 的关联 ID 或封面状态，以免污染新成片。
- 仍用 MediaStore pending + 校验 + 双文件发布事务与恢复日志，不改成裸路径写文件，不使用私有 `live_photo` 数据库列。提交成功后新增公开 MediaScannerConnection 扫描通知，细节见后文。
- 发布/取消/失败时清理两份本应用创建的记录。跨文件可见性本身仍不是系统原子事务。
- 2104、2200/2016 等既有 profile 保留，enum 新值追加避免改变旧诊断 ordinal。

## 必须真机验证

本次核对的 SDK V1 encode 无封面时间参数，干净输入不主动写封面字段；它能继承已有字段，因此不能推广为 2014 协议永远没有封面字段。非首帧 JPEG 可以封装，但当前动效约 2900ms 封面与视频的相册播放衔接，不能由结构校验证明。

需要 X200/X200 Pro/iQOO 15 等实测：导出后原厂相册是否关联成一个 Live、长按能否播放、封面/旋转/声音是否一致、重启相册后是否仍成立；同时确认 X300s version=1 继续走单文件。保存成功、扫描结束、元数据正确均不等于相册播放验收。

## 自动化验证（本次）

- `testDebugUnitTest`：28 项通过，覆盖 livephoto core/importer、直接导出选择、发布交接。
- 新增 2014 golden framing、两端字段、非首帧结构往返、重复封装不膨胀、10 种 profile 的视频内容不变、取消清理、6 个 insert/write/publish 故障点回滚、异常旧尾标拒绝、随机 ID；以及版本边界/默认值/读取异常/手动和已验收优先级测试。
- 全应用主源码与测试源码编译通过；`git diff --check` 通过。
- `lintAnalyzeDebug` 主源码分析完成。新增预期 `PrivateApi` 警告（SystemProperties 反射可能不可用，已处理异常并披露）；完整 `lintDebug` 因离线缓存缺少 Android instrumentation 的 runner/rules/ext:junit 依赖而未完成，不能称全部 lint 通过。
- 没有真实 vivo 相册测试；打包与合入状态以版本交付记录为准。


## 补充：提交后的扫描通知

2014 两份文件均完成 MediaStore 发布并写入 COMMITTED 日志后，使用公开 `MediaScannerConnection.scanFile`，先 JPEG 后 MP4。通过已有且归本应用所有、非 pending 的 URI 查询实际 DATA 路径，不猜路径、不写私有数据库、不另行插入媒体行、不申请广泛存储权限。只扫描同一现有文件，避免通过复制/重新 insert 制造重复项。

每份最多等回调 5 秒，再处理下一份。正常回调时图完成后才扫视频；图片超时仍发出视频请求，迟到回调的完成顺序无法保证。两次回调等待合计最多约 10 秒；系统查询和 API 调用自身不受这个截止限制，因此不是整个流程的硬超时。不使用已弃用的 ACTION_MEDIA_SCANNER_SCAN_FILE 广播。

查询被拒、路径不存在/不可读，报告 PATH_UNAVAILABLE；请求失败、超时、回调无 URI 分别报告；若扫描器返回与原媒体行不匹配的 URI，报告 DIFFERENT_URI，不自动重插入、不删除陌生行。数据在扫描前已提交，扫描失败不回滚、不把导出变成失败或触发重复导出。诊断随 ConversionResult.scanResults 返回，并写不含路径/文件名的日志。

SCANNED 只意味着系统扫描器回调了既有媒体行，绝不代表 vivo 相册已完成 Live 配对。没有自动重扫定时任务；进程在提交后、发起扫描前终止时，已保存文件保留，但不保证本次扫描已发送。

扫描补充验证：30 项 focused 回归通过，新增提交后调用顺序、权限失败/超时继续处理另一文件、扫描失败不回滚/重发、扫描确认不等于相册确认、其他 profile 不扫描测试；无真实 MediaStore/扫描器或 vivo 设备验证。


## beta37 对照结论

与所核对的 Mimo SDK V1 干净输入协议对齐：同名 JPEG/MP4、同28字符ID、两端2014、相同JPEG尾部/MP4 UUID布局、不主动补封面时间、两份写完后先图后视频请求扫描。

有意差异：ID使用随机十进制而非毫秒数补零；保留Filmroom EXIF清理与日期恢复，不继承旧Live字段；使用MediaStore pending事务和回读校验，扫描用公开scanFile而非旧广播。以上为稳健性和scoped-storage适配，不应称与Mimo逐行或全部行为完全一致。

本次正式版本：`0.1.37-beta37` / versionCode `89`，包名仍 `dev.filmroom`，不包含独立首页实验分支。


## vivo 可用性判断（后续接入）

本机 vivo/iQOO 同时读取 `ro.vivo.camera.livephoto.support`：
- `1`：设备声明支持，只作为能力线索；不把 Filmroom 输出标为相册已验收。
- `2`：设备明确声明不支持。优先突出视频按钮，Live 按钮视觉置灰但保留点击；点击 Toast 提示未满足导出条件，编码和发布前均拒绝。
- 其他值、缺失、无法解析或无法读取：读取 `ro.product.model.bbk`，精确匹配此版本 SDK 的 36 项型号名单。匹配只算能力线索；不匹配保留 UNKNOWN，仍允许用户主动尝试。
- version 仍独立决定单/双文件默认次序。support 不决定封装格式；version 也不能证明是否支持。
- 明确 support=2 时，不允许手动 profile 或历史记录绕过。未知时不自动改存 MP4，用户需点击视频导出。

与 Mimo 的区别：该版 SDK 在属性无结论且未命中名单时返回 false。Filmroom 刻意保留 UNKNOWN，避免把更新机型或读取受限误判为不支持。此名单不是完整的 vivo 支持列表。
没有引入专有 SDK/AAR，也没有规避系统属性读取限制。能力判断仍不能检测系统相册是否把已保存文件识别成 Live。

vivo 单独接入阶段验证：36 项 LivePhoto 定向测试及 126 项配方兼容 gate 通过。随后合并 OPPO / 荣耀 / 三星准入和 Toast 按钮行为，最终验证结果见 LIVE_PHOTO_DEVICE_ADMISSION.md。未跑真机相册验证，没有增加版本号或发布。
