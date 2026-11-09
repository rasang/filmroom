# beta27 audio recovery

Restored against `reverse-support/b27/sources/dev/filmroom/effects` JADX output.
This is recovered source, not the lost original source or historical tests.

- `EffectMotionAudio.kt`: new PCM decode/resample + mixing + AAC cache implementation mapped from `EffectMotionAudio.java` and `EffectMotionAudioEnvelope.java`.
- `EffectPreviewAudio.kt`: original beta22 source extended from beta27 DEX with prepare epochs, bounded worker, mixed file selection, and playback-only stop.
- `EffectAudioTrack.kt`: original beta22 source extended with source-motion preparation, cancellation, and exact 3-second EOS.
- `assets/effect_audio/{light_table,optical_leak,optical_leak_slow}.m4a` and `PROVENANCE.txt`: copied byte-for-byte from beta27 APK.

Recovered constants: 48 kHz, stereo, 144000 output frames, 0.3 original source gain, 80 ms fade-in and 120 ms fade-out, 6-second decoder/encoder deadlines, AAC LC 256 kbps. Source is mixed until the source clip ends or mirror closes (hold + transition * 135/225), whichever occurs first. Audio cache preparation is cancellation-aware and serialized. Original no-audio and cache eviction bounds are retained.

Tests added: source envelope boundaries, fades, delayed source start, short-clip gain bound, clipping/base preservation, clip/mirror endpoint.

Android codec behavior, lifecycle cancellation under device stress, audio focus and audiovisual sync require device verification; host unit tests do not establish those properties.
