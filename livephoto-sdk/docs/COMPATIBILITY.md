# 运行时格式选择与兼容性记录（alpha02）

## 使用方式

`VideoToLiveRequest.profile` 现在可省略（null = 自动），以前显式传入枚举的 Kotlin 调用仍可使用。

```kotlin
val sdk = LivePhotoSdk(applicationContext)
val request = VideoToLiveRequest(
    input = videoUri,
    // 不填 profile：自动按本机候选和已有验收记录选择。
    allowExperimentalProfiles = true,
    targetGalleryPackage = selectedGalleryPackage // 可省略；多个相册且无默认时保持未知
)
val selection = sdk.resolveProfile(request)
val result = sdk.convert(request)
// 先在 selection.environment 指定的相册实际查看 result 输出。
// 只有用户明确确认后才调用，不要在 convert 成功回调里自动调用：
sdk.compatibility.recordGalleryResult(result, SupportStatus.SUPPORTED)
```

`availableGalleries()` 返回能够处理 JPEG 查看 Intent 的应用，不是实况支持名单。AAR 声明精确的 image/jpeg VIEW 查询，不申请 QUERY_ALL_PACKAGES。查询被过滤、异常、没有默认相册时，记录为未知，不能推出“不支持”。

## 自动规则

本机品牌只决定初始候选顺序；没有内置任何“该品牌必定支持”的白名单，也没有把用户此前的一次口头反馈写成全机型成功记录。

| 目标 | 初始顺序 |
|---|---|
| OPPO / 一加 / realme | OPLUS → GOOGLE |
| 小米 / Redmi / POCO | XIAOMI_COMPAT → GOOGLE |
| vivo / iQOO | VIVO_SINGLE → VIVO_PAIR_2104 → VIVO_PAIR_LEGACY → GOOGLE |
| 荣耀 | HONOR_LEGACY → GOOGLE |
| 三星 | SAMSUNG_SEF_107 → SAMSUNG_SEF_106 → GOOGLE |
| 其他 | GOOGLE |

顺序是本 SDK 的试验策略，不是厂商保证。vivo 不按 X300 或系统版本切换。非首帧封面不选择只接受首帧的 legacy vivo。优先选最近一次确认成功的候选，否则选第一个尚未知的候选；跳过此环境的失败记录。所有候选都失败时返回 selectedProfile=null，而不是偷偷重新选择已失败配置。

手动 `profile` 优先，可以明确重试有失败记录的配置；仅选择、生成、入库或打开相册均不改变记录。`requireConfirmedSupport=true` 时只允许已有人为相册验收记录的配置，未知也会阻止转换，但不会被写为不支持。

跨机生成：设置 `targetBrand=TargetBrand.VIVO` 等。此时按接收端品牌给出候选，不读取本机相册缓存、不调用本机能力探针，也不允许在生成手机上把结果记为远端验收通过。手动选择另一品牌的 profile 而不设置 targetBrand，含义是测试该 profile 在本机相册的行为。

## 三态与证据

- SUPPORTED：用户对具体输出及本机目标相册明确确认正常，不是 OEM 全型号认证。
- UNSUPPORTED：用户明确确认当前配置在此环境不识别/不播放；不否定其他配置。
- UNKNOWN：没有有效记录、包查询失败、记录损坏/过期或环境变化。权限问题、转码失败和未扫描完成不能记为 UNSUPPORTED。

`result.selection.status` 表示已有环境经验；`result.galleryConfirmed` 仍为 false，因为本次新输出尚未人工验收。`recordGalleryResult` 写记录，不修改不可变的旧 result。不要把这两个状态混用。

记录前再次校验 ROM/相册版本和发布文件 SHA-256；文件被删除/编辑、缺权限、还没有发布或远端目标都拒绝记录。生成错误绝不自动写负面记录。

## 缓存边界

私有存储 `noBackupFilesDir/livephoto-sdk/compatibility`。按机型、品牌/厂商、系统 fingerprint/API、相册包/版本、writer 版本及导出参数建立哈希键。导出参数包括 SDR/JPEG/AVC 路线、尺寸、JPEG 质量、时长、封面位置和声音开关。环境/参数变化不会复用旧记录；90 天过期，未来时间戳或损坏记录视为未知。

缓存只写环境哈希、profile、三态、确认时间和输出摘要，不写序列号、Android ID、源视频 URI、GPS 或账号；不上网。UNKNOWN 可清除该 profile 的记录，`clearRecords()` 可清除全部兼容记录，不删除媒体或发布日志。样例反馈不能保证同参数下所有内容都成功，宿主仍应保留重试入口。

## 可选官方能力探针

没有捆绑小米/vivo/荣耀的授权 AAR，不反射不存在的私有 API。宿主拿到合法 SDK 后，可注入真实查询：

```kotlin
// MiLivePhotoTools 来自宿主自行获得并依赖的官方 SDK，不是本库的类。
val sdk = LivePhotoSdk(applicationContext, mapOf(
    TargetBrand.XIAOMI to DeviceSupportProbe { MiLivePhotoTools.isDeviceSupportLivePhoto() }
))
val hint = sdk.resolveProfile(request).deviceHint
```

探针不提供时 deviceHint=null；返回 null、抛异常或类链接错误均报告 UNKNOWN。探针结果只表示宿主厂商 API 的设备能力，不自动把 XIAOMI_COMPAT 等自写封装标为支持/不支持，也不覆盖用户对具体 profile 的相册验证。只在本机模式运行，不能用生成手机的结果判断另一台手机。

小米公开接入文档：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2003
Android 包可见性：https://developer.android.com/training/package-visibility/declaring

## 测试 App

默认选自动，可以指定目标相册、跨机品牌或手动格式。保存后点“打开目标相册验收”，确认标识、播放、声音、封面及重开行为，再点“播放正常，记住此格式”或“相册不识别此格式，下次换一个”。失败后不会自动批量生成多份文件；下一次用户发起转换才使用另一候选。

alpha02 是新 debug 构建，CI 临时签名可能与 alpha01 不同；不能覆盖安装时卸载旧的独立测试 App 后安装。不是 Filmroom 正式包，不修改主分支。

验证：45 项纯 JVM 选择/缓存回归＋原有 88 项核心检查＋29 项独立媒体检查；Android instrumentation 用例随工程提供，CI 编译它们但不代替真机运行。
