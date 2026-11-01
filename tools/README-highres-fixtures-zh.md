# 12–50MP 合成图片主机测试

这些测试只使用程序生成的噪声图片，不需要用户照片、模拟器、设备或账号。

## 生成图片

需要 本轮验证环境为 Python 3.12.14、Pillow 13.0.0，建议独立虚拟环境。生成约 600 MB 图片，测试中还会创建临时副本，建议至少预留 2 GB 空间。PNG生成逐行进行；JPEG测试数据生成使用Pillow，**它的内存占用不是应用解码内存测量**。

```bash
python3 -m venv .venv-highres
.venv-highres/bin/pip install Pillow==13.0.0
.venv-highres/bin/python tools/generate_highres_fixtures.py /绝对路径/合成图片
```

生成 4000×3000（12,000,000像素）和8192×6144（50,331,648像素，名义50MP）的高熵RGB/RGBA PNG，以及JPEG和90° EXIF JPEG。另生成12MP八方向四色象限JPEG，用独立角点颜色表核对真实ImageDecoder方向。高熵PNG用于验证真实150–200 MB输入，不能用小体积四色图替代该边界。已有噪声图片时可用`--orientations-only`只补方向图片。

## 执行主机测试

```bash
./gradlew testDebugUnitTest \
  -PfilmroomHighresFixtures=/绝对路径/合成图片 \
  --tests dev.filmroom.HighResolutionImportHostTest
```

工程构建将此属性转发为测试JVM的`filmroom.highres.fixtures`。未显式提供目录时，该重型可选套件标记为**SKIP（未执行）**，不能计为通过。提供目录后缺少图片会失败。图片本体不要加入源码压缩包、APK或版本库；仅保留本生成脚本。

## 能证明与不能证明的范围

- 实际生产`PhotoRepository.import()`；主机测试替身提供ContentResolver流，NativeGraphics ImageDecoder读取真实像素。
- 验证原始尺寸、压缩字节数、512长边预览、JPEG方向、流关闭、取消/读取失败清理及旧照片保留。
- **不是PhotoPicker、真实Android provider、MediaStore或设备测试。**
- Robolectric4.14.1的`BitmapRegionDecoder`只是空白Bitmap shadow；此套件不假装验证原尺寸region spool或完整引擎链路。能力负向探针为`HighResolutionCodecCapabilityHostTest`。
