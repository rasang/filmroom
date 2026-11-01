# 持久导出检查点（v1）

这是阶段恢复，不是保存百分比后从头重算，也不是在系统杀进程前等待回调。

## 实际恢复边界

| 已提交阶段 | 保存内容 | 下次继续 |
| --- | --- | --- |
| 2 计算曝光 | 三个完整 f64 胶片受光平面、测光 EV | 光效 |
| 3 处理光效 | 完整光效后的三个 f64 受光平面 | 胶片显影 |
| 4 胶片显影 | DIR 后三个 f64 密度平面 | 颗粒 |
| 5 生成颗粒 | 完整颗粒后三个 f64 密度平面 | 印相和扫描 |
| 6 印相和扫描 | 完整交错 f32 线性 RGB | 重新编码、保存 |

未完成的当前阶段从上一检查点重做。不恢复半遍 FFT、半层颗粒或半个 JPEG。
未完成首次曝光时仍需重新曝光。编码重试使用完整线性成片，不向损坏编码文件追加。
强杀没有机会执行析构；恢复依据已经落盘的数据，不依据 UI 百分比。

## 原子协议与验证

`files/export-jobs/<job-id>/checkpoint/` 独立于可清理的 native cache scratch。
`pending` 中每个完整文件写入后 `sync_all`；同步目录；新 manifest 写入临时文件并同步。
随后重命名阶段目录、同步父目录、原子替换 `manifest.json`、再次同步父目录，最后才移除旧阶段。
因此旧检查点保持到下一检查点完整提交。正常失败保留检查点；显式取消由队列拥有者删除整个任务目录。
恢复时清理不被已提交 manifest 引用的 staging/旧阶段，不删除其他任务或源文件。

Manifest 绑定：协议/算法版本、尺寸、完整运行参数（含随机种子）、胶片/相纸名称、亮度/GPU配置、原始输入 SHA-256、整个随包引擎资料树 SHA-256。保存每个平面的精确长度和 SHA-256。
先验证受限 manifest、固定阶段/文件名、普通文件类型、精确长度和哈希，再复制到可变 scratch。
绝不 hardlink 后原地修改检查点；现有 f64 reader 还检查每个数值为有限数。
损坏、输入/配置/档案变化或版本不匹配会明确拒绝恢复，不悄悄重新计算或混用图片。
算法改变时必须更新 `checkpoint::VERSION`，不能仅依赖应用版本号。

检查点只用于应用私有目录中的崩溃/断电完整性，不是签名认证格式，不接受外部提供的检查点。
路径不会序列化到 native effectiveParams 或可分享诊断结果。

## 资源代价

复制/哈希使用 128 KiB buffer，图像计算沿用有界行块。
为保留旧检查点并提交新检查点，队列 admission 在现有 scratch/input/output 预算外额外保守预留 `48 * pixels + 1 MiB`。
50 MP 额外约 2.4 GB（2.24 GiB）；这不是常驻内存，也不是每次实际峰值。恢复还会校验输入和检查点，增加顺序磁盘读取。
队列必须串行执行，避免独立任务耗尽彼此的工作空间；完成/取消及时删除 job 工作目录。

## 验证

`cargo test -p film-engine --release --features precision-f64`

`tests/checkpoint_recovery.rs` 使用程序生成的 37×29 输入，父测试对独立工作子进程执行真实 SIGKILL，依次覆盖阶段 2–6，然后验证恢复结果与不中断固定 seed 的 CPU 基线逐字节一致；stage6恢复不重新执行图像处理阶段。
另测试参数/源改变、精确长度、内容损坏、损坏已提交 manifest、torn 临时 manifest、提交中取消、恢复副本与检查点互不影响，以及任务目录清理。
模块单测覆盖 SHA-256 标准向量，并在第二个平面复制及 manifest 写入/同步、阶段重命名、manifest 重命名和交换后目录同步处注入 ENOSPC。额外真实 SIGKILL 覆盖新阶段目录重命名后但 manifest 交换前，以及 manifest 交换同步后但旧代清理前；恢复验证始终选择一个完整提交。提交发生任何错误后，当前内存对象禁止重试，必须重新读取磁盘上的权威 manifest，避免目录同步失败后误用旧代状态。
这些是 Linux host 故障注入/真实子进程测试，不声称替代 Android 真机后台限制、断电文件系统或 GPU 恢复验收。

### 真实 50 MP 补充验收（2026-10-31，Linux host）

用私有 6144×8192 原始线性照片、当前默认 Portra 400 / Endura、固定 seed 42、CPU 路线，通过应用同一 `mobile::export` 入口验收：

- 首次到达负片缩略图之后的颗粒步骤时请求取消；stage4 检查点完整保留，partial output 不存在，工作 scratch 全部清除。
- 从同一 job 继续，实际执行阶段只有颗粒和印相/扫描，未重算曝光、光效或显影。最终完整 f32 成片 SHA-256 与此前不中断导出的同照片基线完全一致，源与入队副本均未改动。
- 两段运行结束均清空 disposable scratch；完成后的整个任务目录删除验证成功。
- 每 200 ms 采样观察到工作磁盘峰值 6,241,128,853 B，检查点双代峰值 2,415,923,605 B，不超过额外检查点预留 2,416,967,680 B；轮询值不是瞬时精确峰值，安全 admission 仍按保守公式。
- 第一段约 106.7 s、恢复段约 130.3 s，native 进程 VmHWM 最高 39,220 KiB。这些不是 Android APP 总内存、后台可靠性或手机耗时承诺。
- 已查看同一实际密度生成的 192×256 负片示意图。Portra 档案 450/550/650 nm 片基光密度约 0.815/0.602/0.195；橙色来自档案的波长选择性透射，并非额外橙色覆盖层。

私有原照片、图像产物、照片哈希与绝对路径均留在仓库外，不作为源代码/发布归档的测试素材。

### Android private-directory aliases (0.1.3 regression fix)

`nativeCreate` and spool validation canonicalize existing paths. Android's Java
`filesDir` may still use a different alias of that same private app root. A queue
checkpoint request must therefore canonicalize its **existing parent job** before
comparison; the `checkpoint` directory itself does not yet exist for a fresh job.
Comparing the raw request string to a canonical input job rejected valid exports
before any photographic stage. The correction does not broaden writable roots:
input must remain `input.rgb` in a UUID job under private `files/export-jobs`, output
must share that job, and the requested final name must be `checkpoint`. Existing
checkpoint symlinks (including internal/dangling links), other jobs and external
parents remain rejected.

`QueueCheckpointProbe` calls the production Java JNI entry point with a symbolic
alias of the app root, renders a new queue job, resumes through the canonical root
and compares outputs byte-for-byte. It also checks dimensions, finite output,
wrong basename/UUID/root and symlink/cross-job rejection. The normal host test
recipe includes its synthetic case. An optional local-only fixture invocation is:

```
java -cp <classes> dev.filmroom.engine.QueueCheckpointProbe \
  <host-library> <private-sandbox> <linear-f32-RGB-file> <width> <height>
```

This is a host JVM/JNI regression test, not an Android service-runtime test.
