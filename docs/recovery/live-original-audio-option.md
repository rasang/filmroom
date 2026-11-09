# Viewfinder original Live audio option

- Default enabled. A persistent, viewfinder-only switch remembers the choice across photos and launches.
- Changing it restarts the preview to preserve image/shutter/ambience sync. Pending old audio callbacks are cancelled by the existing stop/replay epoch.
- Preview takes an audio-only source snapshot on construction and replay. Video decoding keeps its original motion source.
- Both export entry points snapshot the same selection; the switch cannot change an active export.
- Disabled means no source audio is passed to the mixer, not global mute: existing effect ambience and shutter assets remain.
- Six new policy/persistence/preparation tests. Integration must run them plus audio/navigation/export regression and build; no device audio verification performed here.
