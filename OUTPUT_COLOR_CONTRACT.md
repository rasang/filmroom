# 输出色彩空间边界

支持且仅支持 `sRGB`、`Display P3`、`Adobe RGB (1998)`；默认 sRGB。原图处理引擎负责将场景处理结果转换到所选线性 RGB 基色，预览路径再转换回线性 sRGB 后形成 Android 位图。编码器不会再次转换基色，也不会把 sRGB 像素仅改标签冒充广色域。

`PhotoRepository.exportLinear(..., outputColorSpace = ...)` → `PhotoCodecs.encode` → JNI JSON `outputColorSpace`。PNG 直接走流式 Kotlin 编码器。无效值明确报错。

文件边界执行 SDR 0–1 裁切：sRGB/Display P3 使用 sRGB 分段传递函数，Adobe RGB (1998) 使用 gamma 563/256 的逆函数编码。JPEG 和 TIFF16 写入对应 RGB ICC；PNG sRGB 使用标准 sRGB chunk，其他两种写入对应压缩 iCCP，不同时写入冲突的 sRGB 或 gAMA 标签。ICC 使用固定版本的 moxcms 矩阵/TRC 配置，移除不适合 RGB 文件的可选视频 CICP 字段，时间戳固定以保证原生编码和 PNG 资源一致。原始照片的 GPS/相机元数据不复制到输出。

验证：Rust 测试解析实际 JPEG APP2 / TIFF ICC，解码像素检查传递函数，利用 ICC 反向变换检查传递函数一致；PNG 主机测试检查 iCCP 解压字节、CRC、标签互斥、实际 IDAT 样本以及错误配置拒绝。此类主机检查不等于 Android 真机显示验证。

重新生成 PNG 配置：在 codecs 目录执行 `cargo run --offline --locked --bin export_profiles -- ../app/src/main/assets/color-profiles`，并同步 `OutputColorSpace.kt` 的 SHA-256 固定值。`bundled_png_profiles_match_native_profiles_exactly` 防止配置漂移。
