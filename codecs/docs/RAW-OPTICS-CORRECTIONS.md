# RAW 光学校正：内嵌参数与 Lensfun

本轮在原有畸变校正上增加暗角与横向色差（TCA），处理相机写入 RAW
的参数以及已准入的离线 Lensfun 配置。TCA 修正 RGB 通道之间的几何位移；
它不是白平衡、色彩风格、纵向色差或通用紫边消除。

## 自动启用范围

| 输入路径 | 畸变 | TCA | 暗角 | 限制 |
| --- | --- | --- | --- | --- |
| Sony ARW 内嵌参数 | 已有 | 新增 | 新增 | 已支持的原始 CFA 布局、已知表结构和数值范围 |
| Fujifilm RAF 内嵌参数 | 已有 | 新增 | 新增 | 已支持的 Bayer / X-Trans；已知 29/31 项 CA 和 19/23 项暗角表 |
| Olympus / OM System ORF 内嵌参数 | 已有 | 新增 | 未启用新模型 | 六个 `0x150c` 系数；机内阴影补偿开关不是可执行增益表 |
| Lensfun 回退 | 已有 | 新增 | 新增 | 仅已准入的 Canon EOS R + RF 16mm F2.8 STM 原始 CR3 布局 |
| CFA DNG 标准 opcode | 保留 | 保留逐平面 Warp 能力 | 保留 GainMap 能力 | 独立标准路径，本轮未改其执行器 |

这些是参数格式和输入布局的支持范围，不代表所有相机、镜头、固件或 RAW
变体都已实测。相机生成的 RGB / YCbCr RAW 不启用本轮厂商 CFA 新模型，避免
对已经处理过的像素重复操作。Sony 的 active area、Fuji / Olympus 的推荐
可见裁切分别作为各自参考坐标域，EXIF 方向在后续输出阶段处理。

Olympus 参考实现说明阴影补偿可能已经写入 RAW 像素；目前没有经过验证的
内嵌暗角系数模型。本轮保留其既有行为，不从开关、镜头名称或测试白墙拟合
额外增益。Sony RX100 II 的三个参数表采用各自的明确节点数，允许实际样片
的 11 / 11 / 16 组合。该扩展的依据和与参考解析器的区别已单独记录。

厂商公式、原始标签类型、坐标约定、参考版本及数值边界见
[EMBEDDED-OPTICS-MODELS](EMBEDDED-OPTICS-MODELS.md)；Lensfun 的严格身份匹配、
实际拍摄光圈和对焦距离区间见 [LENSFUN-FALLBACK](LENSFUN-FALLBACK.md)。

## 每项独立选择，单次执行

暗角和 TCA 各自保留 `Missing`、`Unsupported`、`Ready` 状态。

- `Ready`：采用该文件中已验证的内嵌模型。单位模型同样代表明确的内嵌结果，
  不会再叠加外部配置。
- `Unsupported`：存在参数但不满足已知结构、数值或执行预算，跳过该项并显示
  未应用状态；不会用外部模型悄悄替换。
- `Missing`：若已选择经过身份和布局准入的 Lensfun 配置，可使用该配置对应
  的项目。没有可用模型时保持原有处理。

畸变继续保留一个明确来源。当前 Lensfun 自动选择仅覆盖上述 Canon 原始
CR3 回退；它不意味着为所有已接入的厂商重复查库或扩大数据库启用范围。
一个可选项目失败不会关闭其他已验证项目。例如缺少拍摄光圈或有效对焦距离，
仅跳过 Lensfun 暗角，畸变和 TCA 仍可执行。

处理顺序为：传感器归一化与去马赛克 → 源线性相机 RGB 暗角增益 →
复合畸变 / 各通道 TCA 坐标的一次插值 → 白平衡与颜色矩阵 → 裁切与方向。
暗角的坐标来自未重采样源图。校准临时 RGB 时清除已经执行的光学模型，防止
增益或几何被再次应用。Sony TCA 需要恢复同份畸变模型对应的输出半径；
Fuji 使用源半径；Olympus 按固定 darktable 版本在畸变后的源半径求值。

## 执行预算、失败和缓存

只需要暗角增益时沿用条带处理，不额外建立全幅 RGB 几何临时文件。存在实际
畸变或 TCA 时使用原有磁盘承载的源相机 RGB 和有界窗口重采样，临时文件的
实际字节计入磁盘准入。模型不生成全幅坐标表。取消检查在行处理和重采样中
保留，现有像素数、内存及磁盘预算继续生效。

TCA 的代数合法性不等于采样窗口能放进内存。描述符在报告可执行前，用各段
与多项式极值求得连续 Jacobian 和通道倍率上下界，对每个最多 128×32 的输出
块估算最坏源窗口，加入插值余量，再对照 8 MiB 窗口上限。此处不用有限网格
采样代替连续证明。超过上限只把 TCA 改为 `Unsupported`，保留畸变和暗角；
解码阶段重新读取参数后重复相同准入并核对状态，避免探测与执行使用不同模型。

照片信息中新增八个严格代码，对内嵌 / Lensfun 的 TCA、暗角分别显示“已应用”
或“未应用”。应用层只接受已知代码并映射本地化资源，丢弃原生自由文本。
单位模型可报告已采用该参数，但不单独增加插值或临时文件。

| 新显影策略 | 使用位置 |
| --- | --- |
| `camera-matrix-linear-srgb-embedded-v2` | 已准入厂商内嵌新项目的普通 CFA 路径 |
| `raf-xtrans-linear-srgb-embedded-v2` | 已准入厂商内嵌新项目的 X-Trans 路径 |
| `camera-matrix-linear-srgb-lensfun-v2` | 已准入 Lensfun 回退 |

策略版本参与已有缓存身份。打开旧 v1 工作副本时重新显影并更新状态；成功后
可复用 v2 副本。重建发生磁盘、内存或解码失败时保留原始文件、会话和旧副本，
不自动循环重试，由用户明确重试。没有采用本轮新模型的普通路径保留原策略。
每次重开仍对原片执行最新 metadata probe；缓存命中只复用像素，显示信息采用
新 probe 的结果。因此仅增加“未应用”提示时，无需重解码也能更新照片信息。

## 验证证据

本轮测试结果、生产二进制与输入 / 输出 SHA-256、逐项控制文件的字节修改、
有限值扫描、独立参考误差和宿主应用测试结果记录在
[`tests/public-fixtures/optics-SOURCES.json`](../tests/public-fixtures/optics-SOURCES.json)。
原始 RAW、全幅浮点图和生成的测试二进制不进入代码仓库。

2026-11-14 的最终执行结果：

| 验证 | 结果 |
| --- | --- |
| 完整 release 自动测试 | 307 通过、0 失败；默认忽略 15 个需显式外部样本的用例 |
| 本轮真实参数专项 | 上述忽略项中再显式执行 2 项并通过，分别读取 5 份厂商原件、2 份 Canon 原件；其余 13 项历史外部样本用例不计为已执行 |
| 原生宿主 JNI | 19 项检查通过 |
| App 宿主缓存、提示及恢复 | 49 项测试通过，0 失败 / 错误 / 跳过 |
| 新版实际生产解码 | 10 张光学样片、2 个应保持原状的对照、11 个指定元数据修改控制，共 23 项通过 |
| 新旧输出完整扫描 | 35 份记录、2,525,478,912 个 float32，全部有限且长度正确 |
| 要求输出完全相等的控制 | 8 组逐字节、逐 float 和 SHA-256 比较通过，差异为 0 |
| 原始文件与控制修改 | 原件 SHA 保持不变；全部 11 个控制只改变已声明的字节 |

23 次新版实际解码的最大单进程 Linux `wait4` 峰值 RSS 为 124,534,784 bytes
（118.77 MiB），最大准入估算为 204,620,096 bytes（195.14 MiB），配置预算为
256 MiB。实测 RSS 与预算估算分别记录，没有声称安装了操作系统级 RSS 硬上限。
最多并行两个解码任务。最终 release 构建的 `codec_probe` SHA-256 与上述全部
真实验证及对比图使用的二进制一致。

数值测试直接编译实际 vendored 元数据解析器；厂商 oracle 独立读取原件字节，
Sony 使用直接组合公式，Fuji 使用二分求逆，Olympus 使用固定参考的源半径
公式。Lensfun oracle 使用固定版本 0.3.4 的原生 C API，不以 Filmroom 自身
输出生成期望值。固定参考版本之间存在的差异在模型文档中明确列出。

管线测试检查源空间增益、逐通道坐标、单次组合、各项优先级、全八种方向、
裁切、Bayer / X-Trans 条带接缝、临时磁盘预算、取消和极端参数降级。
真实 RAW 控制只修改已定位的元数据字节，不改变传感器数据，逐字节证明其他
位置不变；关闭新项目后的输出与原分支二进制进行完整文件比较。DNG 和镜头
身份不匹配的 CR3 作为不应改变的对照。

应用层验证在宿主 Robolectric 中运行真实缓存、文件保有、恢复及 ViewModel
逻辑，JNI 使用测试替身；原生宿主 JNI 冒烟和真实解码另外执行。这些记录不
代表 Android 真机端到端测试、所有镜头的物理标定或与完整商业显影器逐像素
一致。比较图统一输出设置，展示本轮变化，不对输入另作自动曝光或色彩调整。
TCA 单项对比两侧保留相同暗角补偿，只将对应 CA 系数置零 / 保留。RF16 主图
采用没有 JPEG 高光剪裁的 9561 砖墙样片；9423 中庭样片原有的明亮屋顶在固定
曝光导出时剪裁增加，不能用于声称恢复了高光或所有紫边。线性 RGB 保留大于 1
的值，JPEG 显示结果另有输出范围限制。

## 复现入口

```sh
# 在 codecs 目录，准备项目既有 Cargo / libjxl 环境
cargo test --offline --locked --release --jobs 2
cargo build --offline --locked --release --lib --bin codec_probe --jobs 2
FILM_OPTICS_FIXTURES=/path/to/originals cargo test --offline --locked \
  --release --test embedded_optics -- --ignored --nocapture
FILMROOM_RF16_CR3_DIR=/path/to/originals cargo test --offline --locked \
  --release --lib public_eos_r_rf16_samples_have_exact_on_file_identity \
  -- --ignored --nocapture
JAVA_HOME=/path/to/jdk17 ./run-host-jni.sh
```

厂商数值 fixture 的独立重算入口为
`tests/oracles/embedded_optics_reference.py`，Lensfun 为
`tests/lensfun_optics_oracle.py`。重建 Lensfun 静态配置使用
`tools/generate-lensfun-profiles.py --check`。源代码归属与许可证保留在
`app/src/main/assets/licenses/CODEC_NOTICES.txt` 及既有第三方声明中。
