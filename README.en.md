<div align="center">

# Filmroom

**Bring your photos into the film darkroom.**

Native Android film simulation · Offline, on-device processing · No account or ads

[简体中文](README.md) · **English**

[Get started](#availability) · [Workflow](#your-first-photo) · [Formats](#supported-formats) · [Build guide](docs/DEVELOPMENT.md)

<img src="app/src/main/assets/film-rolls/kodak_portra_400.webp" width="100" alt="Original Portra 400 film-stock artwork" />
<img src="app/src/main/assets/film-rolls/fujifilm_c200.webp" width="100" alt="Original Fujifilm C200 film-stock artwork" />
<img src="app/src/main/assets/film-rolls/kodak_gold_200.webp" width="100" alt="Original Kodak Gold 200 film-stock artwork" />
<img src="app/src/main/assets/film-rolls/fujifilm_provia_100f.webp" width="100" alt="Original Provia 100F film-stock artwork" />
<img src="app/src/main/assets/film-rolls/kodak_vision3_500t.webp" width="100" alt="Original Vision3 500T film-stock artwork" />
<img src="app/src/main/assets/film-rolls/kodak_ektar_100.webp" width="100" alt="Original Ektar 100 film-stock artwork" />

<sub>Original artwork used in the app's film selector—not product photographs or before/after samples. [Provenance and trademarks](app/src/main/assets/film-rolls/ASSET_PROVENANCE.md)</sub>

</div>

## Start with a roll of film

Filmroom brings film stocks, photographic papers and scanning paths into one mobile darkroom. Choose a stock, shape the exposure, grain and halation, then save a new image. Processing stays on your device, and your original file is never overwritten.

- **18 film stocks, 20 film profiles** — Color negatives, slide films and two pushed Portra 800 variants, plus 6 photographic paper and 2 motion-picture print-film profiles.
- **More than a filter picker** — Film exposure, print or direct-scan rendering, grain, halation and development controls, with original-image comparison, undo and redo.
- **Compose and frame** — Free or fixed-ratio cropping, composition locking and optional film-edge frames with a white-perforation option for negatives.
- **Export in the background** — A queue snapshots each image and its settings, reports progress and supports checkpoints with manual resumption after interruption. Android can still stop background work.
- **Save for your next step** — JPEG, PNG or 16-bit TIFF, with sRGB, Display P3 or Adobe RGB (1998) SDR output.
- **Four interface languages** — 中文, English, 日本語 and 한국어, selectable in Settings.

## Availability

**Current source version: 0.1.16.** APKs will be distributed through [GitHub Releases](https://github.com/rasang/filmroom/releases); no Release or APK asset has been published yet. For now, [build from source](docs/DEVELOPMENT.md) (Chinese guide).

- Requires **64-bit Android 10 (API 29) or later**. Source builds include ARM64 and x86_64 by default; an ARM64-only phone build is also available.
- This is an early preview project. Host builds, algorithm checks and JNI/UI tests exist, but **end-to-end testing on physical Android devices is not complete**. No phone performance or battery-life guarantees are made.
- Existing preview packages use a debug certificate. Your own build may use a different signature and may not install over them. Keep your signing key; release builds are unsigned by default.

## Your first photo

1. **Import** — Choose one photo through the system gallery or file picker. Use the file picker for DNG; see compatibility below.
2. **Choose a stock and adjust** — Tap a film stock, tune exposure, grain and halation, then choose a print/direct-scan path, crop or add a frame as needed. Compare with the original as you work.
3. **Export a new file** — Choose a format, size and color space, then add it to the queue. JPEG/PNG files go to the gallery's “暗房” album; TIFF files go to “Downloads/暗房”.

The [user guide](使用说明.txt) is currently in Chinese. Exports retain supported capture metadata, **including existing GPS and author information**—check before sharing publicly. See the [metadata rules](docs/EXPORT_METADATA.md) for details.

## Supported formats

| Input | Current scope |
| --- | --- |
| JPEG / PNG | System decoding; transparent PNG areas are composited over white. |
| HEIC | A limited subset of ordinary, static 8-bit HEVC primary/grid images; requires a device decoder and sufficient memory. Supported P3 input is converted to sRGB SDR, not edited as HDR. |
| DNG | Supported 16-bit uncompressed strip-based Bayer/linear RGB and tested lossless JPEG (SOF3) strip-based/tiled variants. Reads RAW data rather than substituting an embedded preview. |

| Output | Best for |
| --- | --- |
| JPEG · 8-bit | Everyday sharing; quality 1–100, default 95. |
| PNG · 8-bit | Avoiding another JPEG compression pass; does not recover missing input precision. |
| TIFF · 16-bit | Further editing; larger files, not displayed by every gallery. An 8-bit source does not become RAW. |

**Know the limits:** A `.dng` extension does not guarantee compatibility. HEIC export, other camera RAW formats, AVIF, EXR and HDR enhancement/preservation are not implemented. DNG/HEIC import is limited to 52 megapixels; final encoding is limited to 84 megapixels, subject to format, memory and storage constraints. Zoomed previews have a maximum long edge of 1536 pixels—**not source-resolution detail**. Unsupported variants or insufficient memory/temporary storage produce an explicit failure, rather than silently downsizing a full-resolution export.

Detailed contracts: [HEIC](docs/HEIC_IMPORT.md) · [DNG/codecs](codecs/README.md) · [Xiaomi DNG scope](docs/XIAOMI_DNG_COMPAT.md) · [Previews](docs/LOCAL_DETAIL_UI.md). These technical documents are primarily in Chinese.

## Your photos stay on your device

The app has no network permission, account or upload feature, and does not request access to your entire photo library. It reads only the photo authorized through the system picker. Temporary processing files stay in app-private storage; app backup is disabled.

For troubleshooting, manually share a redacted TXT from Diagnostic Logs and describe your version, device and reproduction steps in [Issues](https://github.com/rasang/filmroom/issues). Logs exclude photos, filenames, GPS and paths. Sharing is your choice; nothing is uploaded automatically.

## Development and documentation

Native Kotlin Android UI with Rust/JNI image-processing and codec components. The app runs offline; resolving build dependencies for the first time may need internet access.

- [Build guide](docs/DEVELOPMENT.md) (Chinese): prerequisites, builds, replacing native components and verification scope.
- [Development history](docs/HISTORY.md) (Chinese): the complete previous README through 0.1.16, including changes and validation limits.
- [Validation record](验证记录.md) (Chinese): tests performed and device workflows not yet covered.

## Credits and licensing

Filmroom's film and paper engine builds on Andrea Volpato's [spektrafilm](https://github.com/andreavolpato/spektrafilm) and the [spektrafilm-rs](https://github.com/turbasvin/spektrafilm-rs) Rust port.

Code is licensed under [GPL-3.0](LICENSE). The relevant film profiles and LUT data are licensed under **CC BY-SA 4.0**; see the [upstream license and data notices](app/src/main/assets/licenses/SPEKTRAFILM_LICENSE.txt). Other components retain their own [licenses and notices](app/src/main/assets/licenses). The reset-settings icon comes from [Material Symbols](third-party/material-symbols-reset-settings/NOTICE.txt).

The film-selector illustrations are original project artwork. Film-edge frames include reference-based redraws and modeled interpretations, not original film scans; [artwork documentation](docs/FILM_STOCK_ARTWORK.md) records provenance and limitations. Kodak, Fujifilm and film-stock names belong to their respective owners and identify profiles only, without affiliation or endorsement.
