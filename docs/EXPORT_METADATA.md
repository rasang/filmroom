# 拍摄元信息保留（0.1.9）

导入时从原始 JPEG/PNG/DNG/HEIC 读取支持的标准 EXIF 字段，在 RAW/HEIC 线性化之前形成有界快照。队列原子 manifest 保存快照；删除编辑器缓存、暂停、重试、进程恢复不依赖原文件继续存在。旧任务没有快照时不伪造拍摄时间，并显示元信息不可用提示。

保留范围：可读取且有效的相机/镜头、曝光/光圈/ISO/焦距、原始/数字化时间及各自时区/亚秒、标准 GPS、作者/版权/描述/用户注释。当前 AndroidX 读取器未公开评分字段，因此暂不能从原图保留评分。AndroidX 可能规范化文本/有理数字符串，不承诺元信息字节级相同；特殊注释只接受明确 ASCII 头或带 BOM 的 Unicode/UTF-16；未知、JIS 或无 BOM 的歧义编码省略并显示提示。AndroidX ExifInterface 1.4.2 负责读取，不用于改写 TIFF。只保留允许列表中的标准语义字段，**不保证任意私有元数据无损复制**：不复制 MakerNote、未知私有字段、XMP/IPTC 原始包、旧缩略图、RAW 校正结构或输入文件的偏移指针。某些字段/容器不被读取器公开时无法保留。ASCII EXIF 文本中的非 ASCII 值不强制转码；注释使用 EXIF Unicode 编码。

每次实际编码前持久记录一个导出时刻；未提交的失败任务重试使用本次新时刻，已提交的成片恢复只复用原有条目、不重复编码；DateTime、OffsetTime、SubSecTime 写同一 UTC 时刻；PNG tIME 写该时刻（秒精度）。DateTimeOriginal / DateTimeDigitized 不改写。原拍摄时区优先取有效原 EXIF、匹配原 XMP，或有界读取的 Nikon WorldTime 相机记录。仅在原时区缺失时，按用户批准的兼容方案：保留原拍摄壁钟字符串，按导入设备在该历史日期的本地时区解释并冻结 epoch；在输出 EXIF 补充兼容 OffsetTimeOriginal，使系统发布后重扫仍能索引原拍摄日。日志以 ASSUMED_IMPORT_ZONE 明确标注推定，源快照字段仍保留“原无 offset”的事实，不把推定称为相机记录。已有明确时区不覆盖，显式无效时区不静默推定。MediaStore DATE_TAKEN 提交该捕获时刻；DATE_ADDED/DATE_MODIFIED 是系统索引（秒），由 provider 管理，不能保证设备相册索引/文件 mtime 与 EXIF 亚秒精确一致。

输出方向为 1，尺寸为实际输出尺寸；ICC/PNG 色彩标签来自实际所选输出色域，不复制输入 ICC。JPEG/TIFF 的事务替换临时增加一份编码文件的磁盘空间，失败时保留原成片并提示。JPEG 只替换 EXIF APP1（TIFF payload 最多 48 KiB，低于 65527 字节上限），保留编码像素与 ICC。PNG 按原有行流式编码后写 eXIf/tIME，不重新压缩图像。TIFF16 保留原文件全部条带与 ICC 字节，追加重定位元信息及新根 IFD，不经 ExifInterface 或 8 位位图重编码。

快照预算 48 KiB，单字段限长且验证类型/分母/日期/时区。无效或超预算字段省略，并在已完成任务卡显示提示。容器写入使用临时文件+原子替换；元信息写入失败不损坏原编码成片，显示保留范围受限提示。无照片/位置元信息进入诊断日志或网络。不为位置保留新增广泛图库读取或当前设备定位权限；可选媒体位置授权见下文。系统选择器若提供已脱敏照片，无法恢复其未提供的 GPS。

验证：主机合成夹具与单元测试，真实 Android 相册索引行为、OEM 解码器及设备端 HEIC 元信息仍需设备验证；未进行设备/模拟器运行验证；发布构建情况见最终验证记录。

诊断补充：本地日志可包含被省略字段的固定标签名、固定原因码及读取校验/输出校验/写入异常阶段，不包含字段值、原始 EXIF、路径、GPS 坐标、序列号或注释内容。问题列表最多 24 项，超限以 ISSUE_LIMIT 标记；无效字段警告不等于照片导出失败。

## 照片位置读取修复

新增可选 `ACCESS_MEDIA_LOCATION`：仅在用户选择“文件”导入时请求一次，用于已选照片内的拍摄位置，不请求当前设备定位，也不为此申请整个图库读取权限。拒绝后仍正常导入；以后可在系统应用权限中允许。对具有权限且能经系统映射的 MediaStore / SAF 原件使用 `setRequireOriginal`；原件路径不可用时继续读取用户已选 URI。

相册选择器使用公开 `android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS` 请求位置元数据，支持该功能的新系统/扩展版本仍以选择器内的用户决定为准；旧选择器可能忽略此选项并脱敏。不会把 picker URI 改造成另一条 MediaStore URI 绕过用户选择。需要保留位置时，优先使用“文件”选择含 GPS 的原件。原照片本来无 GPS、提供方已移除 GPS、云端只提供脱敏副本、拒绝权限等情况均不能恢复位置；已导入的脱敏缓存需重新导入原件。

Live 导出补日期时不再用仅含日期的快照替换现有 GPS / 相机快照；已有快照（包括备份恢复后刻意去除 GPS 的快照）不从其他文件重新补入 GPS。旧记录没有快照时，只在其已保存 URI 的有效读取权限范围内读取标准拍摄元信息。仍不向日志、备份或网络新增 GPS 数据。

本次验证：42 项专项测试通过（包含可选媒体位置授权/拒绝/恢复、URI 隔离、Live 元数据合并、EXIF 容器及真实照片往返）。真实样片为公开 `ianare/exif-samples/jpg/gps/DSCN0010.jpg`，通过 `FILMROOM_GPS_JPEG` 指定本地路径，不提交照片。JPEG/PNG 导出后经纬度一致，10 个 GPS 标签无缺失；AndroidX 对 GPS 时间的小数秒及无效空方向标记有规范化，因此不是字节级无损复制。

真实照片测试运行于 Robolectric，MediaStore 为模拟提供方；验证了应用实际导入、快照和元数据写入路径，但不是手机相册端到端验收。宿主区域解码器不提供真实像素，PNG 测试使用实际 ImageDecoder 像素经生产 bitmap-to-linear 适配器进入行编码器；未覆盖区域解码和胶片渲染引擎。JPEG/PNG 输出均有非空真实图像。设备系统的授权 UI、旧选择器和厂商相册仍需真机验证。

### Expanded GPS matrix (host validation)

`GpsMatrixHostTest` takes an opt-in `FILMROOM_GPS_CASES` directory of public JPEG fixtures (not committed). The verified set contains Nikon P6000 ×2, iPhone 6 ×2, Nokia 8.3 ×1 and a Samsung image without GPS. Eight additional controlled JPEG/PNG inputs cover N/E, N/W, S/E, S/W, positive/negative altitude and leap-day capture dates; a generated no-GPS image is also checked. All 15 inputs passed repository import, frozen/Live metadata and JPEG/PNG output readback (30 outputs). Coordinates, altitude and capture dates are checked; both no-GPS cases remain without invented coordinates. Together with permission/redaction regressions, 43 selected host tests passed.

An independent Pillow comparison found no missing/added GPS tags in the real photos. Nokia matched exactly; iPhone retained all values except existing AndroidX fractional GPS-time normalization; Nikon additionally normalized its invalid direction-reference value. This is not a byte-exact EXIF guarantee. Tests use actual image decode and production import/publication/metadata paths with a simulated provider; they do not validate vendor galleries, RAW/native codecs, full film processing or Live video/container packaging. No real device run was available.
