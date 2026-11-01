# Adreno 部分阶段 GPU 计算

本功能是 **Vulkan 密度曲线插值阶段** 的可选加速，不是整条胶片流水线 GPU 化，也没有已经测得手机提速的承诺。

## 执行路径

- Android 原生构建包含 `adreno-gpu` feature。生产主机版本不申请 GPU。
- 仅接受 Vulkan、Qualcomm vendor ID `0x5143`、名称包含 `Adreno`、集成 GPU 的实际适配器。不按手机型号或“骁龙”名称直接开启。
- 不需要可选 Vulkan 扩展；64 线程工作组，运行前核验存储缓冲区与线程限制。
- 唯一的进程级 GPU 工作线程负责驱动、管线创建以及后续提交；初始化调用者最多等 2 秒，后续每批提交最多等 3 秒，超时永久缓存 CPU 回退，不重复创建线程。不能保证或声称底层驱动线程被终止。
- 设备启动执行已知曲线的 GPU/CPU 全量自检；设备、管线和失败结果按进程缓存，不在每次预览重新初始化。
- 预热后用三次完整图块的中位数比较 CPU 与 GPU（含上传、读回和转换）。未测得至少 15% 收益则使用 CPU，原因与不支持硬件分开记录。启动工作仅在处理线程，不能放 UI 主线程。
- 单次最多 32768 像素，曲线最多 4096 个采样点，处理块不包含空间滤波。
- 每批核验所有结果为有限数，并抽查均匀分布的 64 个以上像素，每个像素检查全部 RGB 三通道。容差为 `2e-5 + 2e-5 * abs(reference)`。
- 设备错误、结果不合格或读回超过 3 秒时，该操作禁用 GPU 并对当前图块走原 CPU 计算。每个图块间的取消仍由原流水线负责。
- `is_gpu()` 故意返回 false，防止核心误选原桌面后端的近似柔光算法。其余算法仍走 CPU。

## 诊断数据

`GpuDiagnostics` 包含 requested、active（本次实际使用）、available（当前可用）、adapter、backend、acceleratedStage、gpuBatches、cpuFallbacks、reason、startupSelfTestPassed。启动自检通过只说明该次设备计算自检，不代表手机端全部流程验收。只保留设备适配器名称，不记录图片内容。

## 验证边界

- WGSL 解析和验证、设备过滤、曲线边界、CPU 回退、密集 f32 数值模拟均可在主机验证。
- 本开发云有 Vulkan loader，但没有 Vulkan ICD/adapter。显式 GPU 执行测试因此无法运行。该测试标为 ignored，需要有 Vulkan 适配器才手动运行。
- ARM64 Android feature 已通过 cargo check；最终 Android 链接与 APK 构建由总装流程执行。
- 未运行模拟器；未在 Find X8 Ultra/其他 Adreno 真机测量。不能称“手机 GPU 实测通过”，也不能保证比 CPU 更快。

构建已核验 Rust/Cargo 1.88.0，脚本与构建说明已统一该版本。
