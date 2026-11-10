# Motion effect RAW decode fallback

The platform decoder remains first choice. A successful decode (including an embedded preview) is unchanged. There is no reliable “wrong-looking image” or “embedded thumbnail” failure signal in this path.

On a platform IOException, the effect input reader excludes file-not-found, source-read errors, permission failure, cancellation and memory failure. It then reads at most 64 KiB of signature bytes. Only RAW/TIFF containers proceed to PhotoRepository; native validation still decides whether the actual file is supported. Provider MIME, filename and exception text do not select the decoder.

Coverage: entry preload, adjacent pages, picker validation, preview and both video/Live export. Rendered results do not use this RAW fallback. Export retains a native source just for its bounds and pixel reads, then discards the copy/spool. Other preview operations close their source immediately after obtaining their owned bitmap. Native imports are serialized. Existing repository memory/disk admission and cancellation stay active. Export's cancellation flag also cancels the native import coroutine.

System decode retains its 6144-pixel budget; the repository's native preview path is limited to 4096. Normal export resolution selection observes the available pixels and can report reduced output.

Diagnostics record a fixed RAW_FALLBACK event with the operation correlation ID, then native success dimensions or failure. No URI, filename, provider text or image content is logged.

Limits: repository-unsupported variants still fail. The existing native RAF decoder is now reachable through RawInputAdapter using the existing bounded RAF signature check; this adds no new native decoder and does not imply support for every RAF variant. Permissions cannot be repaired by native decoding. The successful-platform low-resolution-preview case is intentionally not treated as failure.

## Verification

EffectImageDecoderTest exercises system success, real ImageDecoder rejection of TIFF-container bytes, fallback success, native failure, export bounds/pixel reuse, ownership cleanup, cancellation, permission/missing/memory exclusions, non-RAW inputs and preserved system resolution. Native tests inject a repository result, not a real native RAW decode. Physical-device tests are still needed on a device/RAW pair whose system decoder fails while Filmroom import succeeds.
