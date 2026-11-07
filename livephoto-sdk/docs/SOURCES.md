# 来源与归属

实现基线为 2026-11-07 的五厂商统一核验报告 v7；不是厂商正式承诺。

## 平台

- https://developer.android.com/media/platform/motion-photo-format
- https://developer.android.com/media/media3/transformer/getting-started
- https://developer.android.com/training/data-storage/shared/media
- Media3 1.8.0 EncoderFactory: https://github.com/androidx/media/blob/1.8.0/libraries/transformer/src/main/java/androidx/media3/transformer/Codec.java
- AGP 8.10: https://developer.android.com/build/releases/agp-8-10-0-release-notes

固定构建版本：Kotlin 2.1.21、协程 1.10.2、Media3 1.8.0、AGP 8.10.1、Gradle 8.11.1。不要从较新文档直接复制未验证的 API。

## 私有封装研究来源

- Oplus: https://github.com/Young-Spark/oppo-live-photo-maker/blob/main/src/oppo_live_photo/muxer.py ，核验 blob `fe4933282fc9b0331dcb12613dca780c104cae8e`；选用 Oplus_8388608 配置。
- vivo pair: https://github.com/LengxiQwQ/live-photo-box/blob/741b06f1050ad8a32ee85fb5d780b46932d738fc/LivePhotoBox.Native/src/protocols/vivo_legacy.cpp
- vivo single: https://github.com/LengxiQwQ/live-photo-box/blob/741b06f1050ad8a32ee85fb5d780b46932d738fc/LivePhotoBox.Native/src/protocols/google_v2_writer.cpp
- Honor candidate: https://github.com/LengxiQwQ/live-photo-box/blob/741b06f1050ad8a32ee85fb5d780b46932d738fc/LivePhotoBox.Core/Services/Protocols/HuaweiMovingPhotoProtocol.cs
- Samsung: https://github.com/PetrVys/MotionPhoto2/blob/ff46699215f1071ada579140d73994a4be29a399/SamsungTags.py
- Samsung parser reference: https://github.com/exiftool/exiftool/blob/9b8664dfcf2c609d3cc8925d380e98ad2a379f9b/lib/Image/ExifTool/Samsung.pm

本交付不附上述项目源码或 GPL 测试摘录，使用重新编写的 Kotlin 封装与验证器；不包含小米、vivo、荣耀合作 SDK。XIAOMI_COMPAT 是自写通用/旧 MicroVideo 字段，不继承官方 SDK 的兼容承诺。

新代码 MIT；运行依赖各有许可证与归属，应在宿主发布时保留。FFmpeg、Pillow、JDK ImageIO 只用于开发验证，不打包进 Android 应用。构建合成样例无用户照片；相册结果需真机独立验证。
