# 常规相机 RAW 接入（开发验证中，未发布）

本开发线从 main `83f1c33a` 独立建立，未混入加速/预览补丁。目标为普通 Bayer NEF、CR2、ARW；是否可导入由**内容、像素布局、有效色彩配置、内存/磁盘准入及真实解码**共同决定，不由扩展名、MIME 或型号白名单决定。

## 路由及颜色

- Kotlin 通用 RawInputAdapter / RawSourceDecoder 对经典 TIFF 统一交给 native；native 有界读取根 DNGVersion 分流，DNG 保留原解码器与错误/颜色语义。
- NEF/CR2/ARW 使用锁定 rawler 0.7.2 的实际传感器解码，不读取 embedded JPEG，不降低分辨率。只有四种 2×2 Bayer 排列接受；白平衡、D65 矩阵、黑白电平和矩形裁切必须有效。
- 从真实解码的整数数据归一化，不能用 dummy 的缺省黑电平。按含 8 行 halo 的条带运行原有 DNG 基础显色，在一次 crop/orientation 后写入相同 float32 RGB spool，预览与导出复用。
- `linear-srgb` 在这里指去掉 sRGB OETF 的现有工作空间接口，**不是无裁切的严格场景线性开发**：沿用既有 rawler 矩阵显色中的负值处理与高光范围映射，不为加入相机格式悄改 DNG 颜色。未复刻相机风格、镜头校正或专有完整开发模型。`CAMERA_RAW_BASIC_DEVELOPMENT` 向用户明确说明。

## 资源准入

相机格式仍受 128 MiB 工作上限约束，不因型号提高预算。

1. 元数据 probe：有界 TIFF/EXIF 读取，lazy mmap，不 populate/WillNeed；dummy 像素容器容量为零，NEF dummy 不复制 EOF 压缩 strip。rawler 元数据解析在同线程 opt-in 配额下执行：累计 4 MiB（按数据类型暂存计费）、64 个 IFD，Sony 解密元数据也计费。配额失败是粘性的，即使容错解析器吞掉标签错误也拒绝。
2. 解码峰值：32 MiB 固定余量 + 输入文件映射 + 一幅 u16 sensor；CR2 unslicing 另加一幅 sensor；NEF 仅在 pinned decoder 必须建立 EOF padded buffer 时加实际块大小。Sony 已去除整输入 clone，tile scratch 在固定余量内。各阶段生命周期明确，不把不存在的全幅 float/RGB 加进去。
3. 开发峰值：32 MiB + 已解码 u16 sensor + `activeWidth × (stripRows + 16) × 96`。采用解码/开发两阶段最大值。条带 8–128 行；预算不满足明确 MEMORY_BUDGET，不能暗降采样。
4. 输出先 reserve 真磁盘块；失败/取消删除本操作创建的半成品，不覆盖已存在文件。第三方整幅熵解码尚不支持中途取消，结束后立即检查粘性取消，不将已取消输出发布。

这是工作集保守准入，不是进程/Android JVM/系统内存硬限制。Linux RSS 只能证明该 host 用例，不能冒充真机总内存。

## 本轮边界

- 拒绝 Nikon HE/HE* 与非 Bayer 小 RAW、Canon CR3/C-RAW、Canon mRAW/sRAW、Sony 小尺寸 YCbCr、Sony ARW6。
- 本分支 Sony FileFormat 主版本仅接受已选样本覆盖的 3/4/5（含 A9 III 全尺寸 lossless 样本）；其他未验证主版本不自动开放。NEF 无压缩目前验证 packed 12-bit / unpacked 14/16-bit 行布局，其他 padding/packing 需要新增实样验证。
- Sony ARW1/历史非标准 SubIFDs 布局（如样本 A100）未适配，明确拒绝；不依型号绕过 TIFF 结构检查。
- NEF 多条带必须严格连续，避免 pinned decoder 拼接假设导致错解。压缩 NEF 保留原 padded buffer/BitPump 算法，仅在完成解码后检查实际消费 bit 数不得超出声明 payload；peek 的未消费 lookahead 不算越界。删尾 1/2/8/16 字节且同步改声明长度的坏样均须拒绝。
- Canon 与 Sony lossless JPEG 仅接受本轮验证的 12/14 bit、无降采样、单扫描、无重启布局，读取完整熵符号与 EOI；不允许 padded entropy reader 将截断补零冒充成功。
- Sony lossless tile 仅允许 EOI 后至多 15 个 `FF` 字节使原块对齐 16 字节；去掉这一容器填充后仍完整验证每个熵符号和 EOI。无填充流保留原严格规则，Canon/DNG 不采用此规则。
- 大图可能在 128 MiB 下被拒绝。必须以最终样本矩阵列出成功、预算拒绝、未支持与失败，不能笼统宣称支持全部型号。

## 验证（待执行的不是已通过）

本地测试包含所有 Bayer 相位、奇偶 active origin、不同 stripRows 的全幅参考逐 float-bit 比对；内存上限、零容量 dummy、metadata 配额粘性；公开 CC0 多机型实样见独立样本清单。最终记录会列出实际执行命令、样本 SHA256、输出尺寸/状态与 RSS。Android ABI/设备运行和 APK 发布不在本次已验证声明中。

### 相机 metadata 的共享内存账本

Kotlin 用最多 256 条根 IFD 的只读资源提示判别是否可能进入相机 profile 路径；这不是格式支持判定。DNGVersion 路径仍沿用原有 metadata-only 恢复，不要求重型预留。非 DNG 经典 TIFF 的 nativeProbe 使用同一 NativeWorkReservations，估计 **32 MiB + 原有 16 MiB 操作余量**，同样受两个并发名额、系统可用内存证据、取消等待和 finally 释放约束；解码已经预留 128 MiB，不嵌套申请。

32 MiB 覆盖有界 metadata 暂存和 RawLoader profile 首次初始化，实测首批相机 probe 进程 RSS 约 25 MiB。profile 是一次初始化后常驻的数据库，释放账本 lease **不表示这部分 RSS 已释放**；之后的 OS available-memory 快照已反映常驻成本，解码 BASE 也保守保留该成本。没有把整个输入文件/传感器加载到 metadata probe，也没有把 host RSS 当 Android 总内存保证。
