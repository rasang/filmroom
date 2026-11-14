# Current performance branch scope

The standalone finishing tab and its exposure/contrast/saturation grade are removed,
including app parameters, serialization, native ABI, rendering passes and final-grade
thumbnail publication. Existing brightness, film, print and scan controls are unchanged.

Retained optimizations:
- Post-grain density cache for existing print controls, bounded by the existing slot budget.
- Scoped GPU output-buffer reuse.
- Exact 8-bit codec conversion optimization.
- Pre-imaging source identity depends on frame snapshot and imprint, not downstream edits.

Compatibility: local experimental recipes ignore retired finishing fields, as other
unknown fields are ignored. Imported QR recipes with unsupported finishing fields are
rejected rather than silently rendering a different grade. Interrupted temporary spool
cleanup is retained for files left by the removed feature. No new finishing files are made.

No dev/main merge or release is part of this change.
