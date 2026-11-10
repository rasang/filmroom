# 动效音频：本地构建与许可边界

beta20 的代码与已交付安装包一致，但公开仓库不提供包含第三方实录的三条独立音轨，也不包含原始 MP3。这是素材许可边界，不是遗漏。没有生成这些文件时，不能将克隆后的声音行为视作发行包的等价验证。

## 自行取得输入

请访问以下原作者页面，阅读并遵守 Pixabay Content License，再下载录音。不要把下载原文件作为独立素材公开再分发。

- Nikon F3 shutter，whoisbenjamin：https://pixabay.com/sound-effects/film-special-effects-nikon-f3-film-camera-shutter-sound-236350/
  - 本次构建输入 SHA256：`b53604cdf616797b49f52067e8f016a411e17a90305f819727c17464c0a258ab`
- Light Switch，SoundReality：https://pixabay.com/sound-effects/film-special-effects-light-switch-156813/
  - 本次构建输入 SHA256：`4f06caae742659ee94bb73291cc5cc47e5b6d6e15c9b292476fcaf747828fdb4`
- 许可：https://pixabay.com/service/license-summary/ 和 https://pixabay.com/service/terms/

## 生成

安装 Python 3 与 FFmpeg，把录音保存在仓库外，运行：

```sh
python3 tools/effect-audio/generate-recorded.py /absolute/path/f3.mp3 /absolute/path/switch.mp3
python3 tools/effect-audio/verify.py
```

生成 `app/src/main/assets/effect_audio/` 下的 `light_table.m4a`、`optical_leak.m4a`、`optical_leak_slow.m4a`。参数、片段、来源见同目录 `PROVENANCE.txt`。对焦双音和轻微底噪是项目原创合成，已认可的录音按时间点混合进三秒音轨。

不要将生成的独立音轨当作素材包公开再分发。不同 FFmpeg 版本可能产生不同编码字节；应检查可解码格式、时长、音量与事件时序，不把哈希不同直接判为声音内容不同。

## 验证边界

beta20 交付经过 191 项专项测试、完整生产代码编译及签名检查；完整测试、lint、真机音频/视频编码与相册播放尚未验证。音频素材准备也不能替代这些运行验证。JNI 原生库准备仍按 `docs/DEVELOPMENT.md`。

## 发行包强制门禁（beta39 遗漏修复）

独立音轨仍是受许可限制的私有构建输入，不提交到公开仓库。它们不是可选资源：
预览使用 AssetManager.openFd；导出也必须准备同一音轨。缺任一文件时禁止继续构建，
不能静默构建无声且导出失败的安装包。

- `verifyEffectAudioAssets` 在所有资产准备前校验三条文件与受版本控制的
  `assets/effect_audio/SHA256SUMS`。每次执行，不让增量资产缓存掩盖源文件丢失。
- `assembleDebug/assembleRelease` 还会执行 `verifyDebugApkEffectAudio/
  verifyReleaseApkEffectAudio`，校验成品中的路径、字节一致性与 STORED 存储方式。
- 重压缩、签名后再次运行：
  `python3 tools/effect-audio/verify-package.py /path/to/final.apk`。
- 音轨重新生成时，先运行 `verify.py` 解码/音量检查并试听，再有意更新
  SHA256SUMS；不得为了通过构建而自动接受任意字节或补空音轨。

本次从已发行 beta38 APK 恢复私有构建输入，APK SHA256：
`7cb03a8d95c26838a624f6747fdcd048969c976f781d5a2fa855a46912f68c91`。
恢复后与 APK 内原字节一致，三条音轨均通过现有 AAC/48kHz/mono/3秒、音量、
事件窗口与端点检查。公开仓库只保存校验清单，不发布独立音轨。

持有这个已验证安装包时，可在自己的构建环境运行：
`python3 tools/effect-audio/restore-from-beta38.py /path/to/beta38.apk`。
该工具先校验 APK 和各音轨哈希，拒绝不同的安装包或覆盖不同的本地音轨；
只恢复私有构建输入，不上传或公开独立素材。
