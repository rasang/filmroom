# Effect previews / 动效展示

These are reproducible **offscreen renders**, not Android screen recordings or AI-generated simulations of the UI.

- Source snapshot: `d5f1360d74bab8114e55c600c0e637e374e7deca` (`dev`, beta22).
- The unmodified `EffectTimeline.kt` produces 90 states at 30 fps for each 3-second effect.
- Unmodified `EffectTextures.kt` and `EffectGeometry.kt` produce Canvas textures via Robolectric native graphics, Android API 35.
- The vertex and fragment GLSL are extracted verbatim from `EffectGlRenderer.kt`, then run in a Mesa EGL/OpenGL ES offscreen context with the same uniforms and texture filtering.
- Original geometric architecture illustration drawn for this documentation. No user photographs, downloaded test originals, personal metadata or third-party audio are included. The illustration is a neutral visual fixture, not a demonstration of film-color processing.
- The readout uses explicit example values `100` and `5.6`, not inferred EXIF.
- MP4: H.264, yuv420p, 30 fps, 3 seconds, no audio. GIF: 480 px wide, 15 fps, 128 colors, looping. GIF palette/frame-rate reduction is not the app's output quality.
- Not an Android MediaCodec/Live container compatibility test. No performance or device-parity guarantee.
- The demos show one normal-speed landscape playback; interactive paging, portrait orientation and slow mode are not demonstrated here.

中文：使用当前分支的真实时间线、Canvas 纹理和 GLSL 离屏生成，不是实机录屏。输入是专为文档绘制的原创建筑插画，无私人照片和第三方音频；不展示胶片色彩算法效果。参数为示例值。GIF 经过降帧、缩放与减色；MP4 无声。仅展示横向普通速度单次播放，不证明 Android 编码器、Live 容器、设备性能或滑动交互已验证。
