# HEIC 验证清单（2026-10-31）

生产代码已完成独立只读审查；NCLX颜色回退发现已修复并复核关闭。未更改发行版本号或发布产物。

| 门禁 | 结果 | 边界 |
|---|---|---|
| 全量host + 显式高分辨率fixtures | 327项，325通过、2独立JNI opt-in跳过，0失败/错误 | 9项12MP/50MP已有fixture套件实际运行 |
| 独立生产JNI队列 | 2/2通过，0skip | HEVC像素和MediaStore是明确替身；实际canonical输入→engine→JPEG/TIFF codec为真实库 |
| 最后grid+普通thumbnail fixture HEIC子集 | 30/30通过 | 容器准入/host lifecycle，不是平台HEVC |
| lintDebug | 0error、53warnings | 新路线沿用保守usableSpace检查，含对应建议warning |
| assembleRelease | 通过 | unsigned构建非交付安装包 |
| assembleDebugAndroidTest | 编译通过 | 未运行设备/模拟器 |
| 真实HEIC host fixture解码 | 9个成功 | ImageMagick/libheif，不是Android codec |

## 可重复命令

```sh
source /workspace/shared/toolchains/env.sh
export JAVA_TOOL_OPTIONS=-Duser.home=/workspace/shared/toolchains/java-home
unset FILMROOM_HOST_JNI_DIR
gradle --offline -PfilmroomHighresFixtures=/workspace/shared/filmroom-highres-fixtures \
  :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest

# 独立JVM，避免跨Robolectric SDK sandbox重复load JNI。
export FILMROOM_HOST_JNI_DIR=/workspace/shared/filmroom-real-chain/libs
gradle --offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -I tools/host-jni/real-queue.init.gradle :app:testDebugUnitTest \
  --tests dev.filmroom.export.RealNativeExportQueueHostTest
```

本次原始XML、日志暂存工作区 `/workspace/shared/filmroom-heic-validation/`；这不是运行时路径、打包依赖或网络地址。最终发布应保留这些证据副本，并按发布版本重新完成签名、ABI、ZIP/ELF对齐与包内源库一致性检查。本次功能实现阶段未签名/上传/交付安装包。

## 标准 ICC P3 修复门禁（待发布）

独立只读审查已完成：无阻塞发现；这是代码及 host 证据 signoff，不是 Android HEVC/color 运行证明。

实际失败文件可在 0.1.7 的 ICC 禁止分支重现拒绝；新解析器识别原尺寸 4416×5888 的 108-tile 主图以及每 tile 的标准 Display P3 ICC。原始文件还包含厂商私有 gainmap 尾部，不解释/传递它；此修复只规范化 SDR 主图，不是 HDR 支持。私有照片及 profile 均未加入仓库素材。

- 全量 host + 显式高分辨率 fixtures：344 项，341 通过、3 独立 JNI opt-in skip，0 失败/错误。九项既有高分辨率测试运行。
- 独立真实 JNI：3/3，0 skip；新增两种原创 ICC P3 fixture，经明确替身 HEVC 输出独立 LittleCMS sRGB 参考像素，规范化 spool→真实 engine→真实 JPEG/TIFF codec；MediaStore 仍为替身。
- lintDebug、assembleRelease、assembleDebugAndroidTest 通过。未执行设备/模拟器；instrumentation 增加真实平台 P3 转换与 tile-only ICC grid 断言。
- 实际私有照片在主机 libheif 原尺寸解码并以其 ICC 经 LittleCMS 转换 sRGB，原文件 SHA256 不变。这不是 Android codec 验证；平台如不正确识别 ICC，仍安全拒绝。后续窄 SDR-base 策略允许去除已核验 SDR 底图的独立 gainmap；API36+ 反向或未知方向仍拒绝。
- 原创 fixture 的 ICC、HEVC、LCMS 参考独立生成，无用户图像/ICC 数据；现有 NCLX-only P3 仍作拒绝回归。

日志和 XML：工作区 `/workspace/shared/filmroom-heic-user-validation/`，不打包进应用。

### SDR 基底附层处理补充

全量最终测试：355项，352通过、3独立JNI opt-in跳过，0失败/错误。新增11项API29/34/35 native Bitmap测试：包括不可变底图，附加非平凡gainmap再移除后所有像素不变；canonical float spool与无附层底图逐字节一致；反向/未知方向与错误底图格式拒绝；失败回收。API36方向getter以公开API运行时查询，当前host测试只覆盖策略值，未执行API36。

首次新增API34测试因宿主缺Robolectric android-all依赖与Java TLS下载错误出现5个初始化失败。已从官方Maven下载宿主依赖并核对jar/pom SHA512，重跑11项全部通过；原失败日志保留，不把它们算跳过。没有新增生产依赖。

SDR附层补充也已独立只读复核通过，无阻塞发现。API36反射分支仅官方契约/源码审查，未做API36运行测试；厂商HEVC实际解码仍未知。

最终独立生产JNI套件再跑3/3通过，0skip/fail/error；日志sdr-base-final-real-jni.log。生产Gradle已释放；版本仍0.1.7/code8，未签名/打包交付/上传。
