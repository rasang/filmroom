# RAW import batches

This app-level preference changes only the output-row working batch during a single RAW import. It does not change resolution, reconstruction, color policy, film rendering/export tiles, or the number of simultaneous photos. It is not a JPEG compression-tile-size setting.

Settings offers Auto (recommended), Small, Standard, and Large. The persisted IDs are `auto`, `small`, `standard`, `large`; unknown stored values use Auto. A photo reset does not reset this preference. Each import captures the setting before copying its input. The native memory request is captured before waiting for its existing worker lease; neither snapshot changes while queued. Later settings changes affect later imports only.

## Selection

* Standard reproduces the original format-specific row plan, including its memory clamp.
* Small requests half that plan, with the existing eight-row admissibility floor.
* Large requests twice that plan, but clamps to the capacity computed by the original base, halo, codec scratch and live-buffer accounting. Enlarged plans use even row counts. A preference cannot rescue an inadmissible input or increase its memory allowance.
* Auto keeps all camera RAW and three-component Linear DNG plans unchanged. For single-component Bayer DNG, it uses the bounded Large plan. The host evidence for this narrow default is the three real OPPO/Xiaomi/vivo DNGs in the private validation report. It is not a phone-wide speed guarantee.
* General row caps remain bounded at 256, Nikon HE at 160, and Nikon small NEF at 128. The decoder's intrinsic HE 64-row callback, Sony small RAW 512-pixel tiles, JPEG blocks, TIFF strips and source tiles are unchanged. Fixed-size routes can ignore this preference, as the settings description explains.

The Android DNG request remains at most 128 MiB; supported camera RAW requests remain at most 256 MiB. Existing global dynamic reservations and decoder phase ownership are unchanged. The native generic DNG API historically permits requests up to 4096 MiB; this setting does not change that API limit or cause the Android caller to request it. The complete process RSS is not identical to a per-operation admission budget.

No extra memory margin is silently removed to obtain larger rows. When a larger request cannot fit, the admitted row count is clamped before allocating buffers. A decode still may fail for genuine allocation, I/O, corrupt-input or memory-pressure reasons; this option is not an absolute crash prevention or speed promise. Existing cancellation guards and temporary-output ownership remain in force.

## Validation

Private host evidence and exact source provenance are kept outside the repository under `/workspace/shared/filmroom-raw-batch-validation`. Original user photographs are not included in source, reports or patches. Synthetic 50/52 MP fixtures are explicitly labeled synthetic and do not establish coverage of an actual OPPO 50 MP camera file. Full byte hashes, metadata and dimensions must match the frozen Beta1 baseline for every recommended mode.
