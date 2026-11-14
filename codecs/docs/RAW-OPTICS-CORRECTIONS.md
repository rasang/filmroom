# RAW 光学校正：内嵌参数与 Lensfun

RAW 显影分别选择畸变、横向色差（TCA）和暗角模型。内嵌参数优先；只有对应
项目缺失时，才尝试已匹配的离线 Lensfun 标定。当前已编译固定快照的完整
**56 个 XML、1,580 条镜头记录、1,059 条相机记录**，生产选择不再限于 RF16。
全部记录可供匹配，不代表每张 RAW 都能自动应用三项，也不代表所有镜头均有
真实样片验证。

TCA 修正 RGB 通道之间的几何位移；白平衡、色彩风格、纵向色差和通用紫边
消除不属于该模型。暗角增益作用于尚未重采样的线性相机 RGB。

## 自动选择范围

| 输入路径 | 畸变 | TCA | 暗角 | 来源与准入 |
| --- | --- | --- | --- | --- |
| Sony ARW 内嵌参数 | 支持 | 支持 | 支持 | 已支持的原始 CFA 布局、已知表结构和数值范围 |
| Fujifilm RAF 内嵌参数 | 支持 | 支持 | 支持 | 已支持的 Bayer / X-Trans；已知 29/31 项 CA 和 19/23 项暗角表 |
| Olympus / OM System ORF 内嵌参数 | 支持 | 支持 | 无新系数模型 | 六个 `0x150c` 系数；阴影补偿开关不是可执行增益表 |
| Lensfun 原始相机 RAW 回退 | 按项选择 | 按项选择 | 按项选择 | CR3、CR2、NEF、ARW、RAF、ORF、PEF、RW2 的既有 CFA 解码布局，严格身份匹配与连续数值准入 |
| Lensfun 原机 CFA DNG 回退 | 按项选择 | 按项选择 | 按项选择 | 正向原机 firmware/source 证据，且无 opcode、转换标记或其他排斥条件 |
| CFA DNG 标准 opcode | 保留 | 保留逐平面 Warp | 保留 GainMap | 独立、有序的标准路径，不叠加外部回退 |

这次扩大的是已有原始 RAW 解码器中的配置选择能力，没有承诺所有相机固件、
压缩模式、传感器布局和像素尺寸。RGB / YCbCr RAW、LinearRaw、JPEG、HEIC 和
已有线性工作副本不按未处理 CFA 数据接入外部回退。

完整快照中有畸变数据的记录为 1,538 条，TCA 为 1,021 条，暗角为 717 条，
三项齐全的为 591 条；39 条只有 TCA 或暗角而没有畸变数据，也可以独立选择
其可用项目。1,580 是数据库记录数，包含同型号不同标定画幅、固定镜头相机
和附件组合；去重后的 maker/model 名称为 1,481 个。清单与逐文件 SHA 见
[`DATABASE-STATS.json`](../lensfun/DATABASE-STATS.json) 和
[`PROVENANCE.json`](../lensfun/PROVENANCE.json)。

机身、镜头、卡口、标定画幅和实际焦距须一致。匹配采用明确别名与规范化后的
精确名称，不使用模糊相似度；不同光学型号仍视为歧义。无名称镜头只在固定
相机私有卡口等证据足够时解析；转换镜、增距镜组合不能自动当作裸镜头。
固定镜头记录中明确分离的附件焦段按精确组选择，不跨组做变焦插值。

Sony 的 active area、Fuji / Olympus 的推荐可见裁切分别作为其内嵌参考域。
外部配置以未旋转的默认可见裁切为参考，组合时保留各自原点与尺寸。Sony
RX100 II 的参数表允许各自明确的节点数，例如已经验证的 11 / 11 / 16 组合。
Olympus 没有经过验证的内嵌暗角系数模型，不能从阴影补偿开关、镜头名称或
测试白墙推造一份增益表。阴影补偿可能已经写入 ORF 像素；当前没有证明未写入
的来源合同，所以即使镜头、光圈和距离齐全，也不自动给 ORF 叠加外部 PA 暗角。
这项来源限制保留其畸变 / TCA 的独立选择，也不捏造检测到了不支持的增益表。

厂商公式、原始标签类型、坐标约定和固定参考版本见
[EMBEDDED-OPTICS-MODELS](EMBEDDED-OPTICS-MODELS.md)；完整匹配、插值和来源契约见
[LENSFUN-FALLBACK](LENSFUN-FALLBACK.md)。

### DNG 原机来源门槛

DNG 回退需要正向证据：Software 字段规范化后以原始相机型号开头，并紧随
版本 / firmware 标记。缺少已知转换器名称不能替代这项证据。独立 RAW 描述符
还须与 DNG 读取器的 CFA 类型、传感器尺寸、active、crop 及已有方向字段一致。

任何 OpcodeList1/2/3 标签都会阻止外部回退，包括空、可选或未知 opcode；
已有 warp、gain map、Stage1 操作、既有 DNG profile 路径、OriginalRawFileName /
Data、Adobe 转换私有标记以及非单位或非法 DefaultScale 同样排斥。缺失原机
证据时保留原有 DNG 路径，不猜测来源。已准入的回退在最后仍调用 DNG 专用
`calibrate_camera`，保留 DNG 的色彩、白平衡、曝光处理。标准 opcode 的顺序、
必需操作错误和 LinearRaw 行为维持其独立契约。

## 每项独立选择，单次执行

畸变、TCA 和暗角各自按来源状态选择：

- `Ready`：采用已验证的内嵌模型。单位模型也是明确的内嵌结果，阻止同项外部回退。
- `Unsupported`：参数存在但结构、数值、来源或执行预算不满足要求；不以外部模型替换。
- `Missing`：已匹配 Lensfun 配置有对应标定时，尝试该项准入；没有数据时保持原处理。

畸变与 TCA 各只有一个执行来源。例如可组合内嵌畸变、外部 TCA 和外部暗角，
而不会重复做两遍畸变。实际焦距缺失会阻止可靠匹配；实际光圈或有效对焦距离
缺失、异常或不稳定，只阻止依赖它们的暗角项，不用镜头最大光圈或猜测的
无穷远替代。对焦距离为区间时，连续检查完整区间的模型增益变化。

通用实现支持 PTLens / poly3 / poly5 畸变、linear / poly3 TCA 和 PA 暗角。
畸变与 TCA 按固定 Lensfun 0.3.4 进行多焦距插值；PA 使用焦距、光圈、距离
三维插值。重复键覆盖、混合畸变模型的选择和最近标定端点遵循固定原生版本，
不混合不同公式族。大表上限为 1,024 项，覆盖库内最大 301 条畸变和 440 条
暗角表。特殊投影保持镜头原投影，不自动把鱼眼变成直线投影。

处理顺序为：传感器归一化与去马赛克 → 源相机 RGB 暗角增益 →
复合畸变 / 各通道 TCA 坐标的一次插值 → 相应色彩校准 → 裁切与方向。
暗角在实际 active 源矩形上证明安全，不再假设其仅超出 crop 0.5%。源空间增益
和几何执行后清除临时 RGB 上对应状态，防止再次应用。

## 执行预算、失败和缓存

只需要暗角时沿用条带处理，不额外建立全幅 RGB 几何临时文件。实际几何操作
使用磁盘承载的源相机 RGB 和有界窗口重采样，临时文件字节计入磁盘准入，
不生成全幅坐标表。原有取消检查、像素数上限及内存 / 磁盘预算继续生效。

组合边界采用完整输出矩形的连续区间细分，先收紧包围再尝试增加统一缩放。
这避免把中心径向倍率的过度估计变成不必要的画面裁切；6000 像素 RF16 和
参考原点偏移均有视野回归。有限网格或几个角点不充当全域安全证明。

另用全域 Jacobian 和通道倍率差估计每个最多 128×32 输出块的最坏源窗口，
加入插值余量后对照 **8 MiB** 上限。边界细分通过不会绕过窗口检查。可选项目
准入失败时不强行读取更大窗口，也不替换已准入的内嵌模型。实际解码重新读取
参数并核对计划，防止探测和执行使用不同状态。

照片信息分别报告内嵌 / Lensfun 操作的已应用或未应用状态。配置根本没有该项
数据，与有数据但拍摄参数 / 来源 / 数值准入失败是不同情况。应用层只接受
已知状态代码并映射本地化资源；单位内嵌模型仍能阻止外部重复操作。

| 显影策略 | 使用位置 |
| --- | --- |
| `camera-matrix-linear-srgb-embedded-v2` | 已准入厂商内嵌项目的普通 CFA 路径 |
| `raf-xtrans-linear-srgb-embedded-v2` | 已准入厂商内嵌项目的 X-Trans 路径 |
| `camera-matrix-linear-srgb-lensfun-v3` | 相机 CFA 路径实际采用完整 Lensfun 库中至少一项操作 |
| `raf-xtrans-linear-srgb-lensfun-v3` | Fuji X-Trans 实际采用外部配置 |
| `dng-cfa-linear-srgb-lensfun-v3` | 通过原机来源检查的 CFA DNG 实际采用外部配置 |

策略版本参与缓存身份。此前 Lensfun v1 / v2 工作副本不能当作 v3 输出复用；
从保留的原始文件重新显影并完成新副本后，才更新缓存身份。未采用外部操作的
路径保留对应策略。重建失败或取消时保留原始文件、会话和旧副本，仅清理新建
输出，沿用手动重试，不自动循环。重开会执行最新 metadata probe；仅提示有
变化而像素策略未变时，照片信息仍可更新。

## 当前数值验证与证据边界

完整库参数范围 oracle 通过官方原生 Lensfun 0.3.4 加载全部 1,580 条记录，
核对 6,320 个焦距 / 光圈范围字段。新增光学 oracle 覆盖 11 个 profile、15 组
参数、135 点，包括多焦距、301 / 440 项大表、linear TCA、不同原生投影、重复
键与混合模型，以及没有畸变标定时的独立 TCA / PA。期望值来自固定原生库，
不由 Filmroom 算法自己生成。

新增对照的最大坐标误差为 0.000597730 px，暗角增益最大相对误差为
0.000000454；TCA 系数逐 float 相同，PA 系数最大差约 5.96e-8。旧 RF16 和
几何 oracle 仍作为回归保留。圆形鱼眼在矩形角落无效的 PA 模型明确拒绝，
不复制原生色彩回调的零值 / 截断结果。

这些是参考公式一致性和特定模型的证据，不代表全库真实拍摄、APK 增量或
Android 真机内存。本轮最终构建、真实 RAW 和应用测试结果已绑定各自
源文件 / 二进制 SHA，见 [完整库验证记录](LENSFUN-FULL-VALIDATION.md) 和
[逐样片来源与哈希](../tests/public-fixtures/lensfun-full-SOURCES.json)。
不能沿用下面旧版本的总数或旧输出哈希；XML 和生成代码字节数也不能代替实际 APK 增量。

## 历史内嵌与 RF16 v2 生产验证

以下记录属于此前的内嵌参数 / RF16 专项实现，不是完整数据库 v3 的最终门禁。
旧 DNG 对照保持不变的结果不约束新的原机 CFA DNG 回退；符合新增来源门槛的
DNG 可以在 v3 中选择外部配置并生成新工作副本。


该历史阶段的测试结果、生产二进制与输入 / 输出 SHA-256、逐项控制文件的字节修改、
有限值扫描、独立参考误差和宿主应用测试结果记录在
[`tests/public-fixtures/optics-SOURCES.json`](../tests/public-fixtures/optics-SOURCES.json)。
原始 RAW、全幅浮点图和生成的测试二进制不进入代码仓库。

2026-11-14 的历史 v2 阶段执行结果：

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
`tests/oracles/embedded_optics_reference.py`；Lensfun 的旧 RF16 对照为
`tests/lensfun_optics_oracle.py`，全库代表光学对照为
`tests/lensfun_full_optics_oracle.py`，完整参数范围对照为
`tests/lensfun_database_oracle.py`。重建 Lensfun 静态配置使用
`tools/generate-lensfun-profiles.py --check`。源代码归属与许可证保留在
`app/src/main/assets/licenses/CODEC_NOTICES.txt` 及既有第三方声明中。
