# Changed-class audit

An independent audit of `reverse-support/source-diff.json` found no additional substantive feature changes outside the restored effects, capture metadata and motion diagnostics paths (version identity remains a separate release step).

Most decompiler differences elsewhere are propagated `SafeInfo.motionPreview` parameters/default masks, synthetic method identifiers, equivalent constant references, or exception declarations. The suspicious `FilmEngine.renderInternal` coroutine `Retain.close()` movement was checked in fallback instruction output: release paths match; the structured Java difference is decompiler reconstruction noise.

This is a code-evidence comparison, not proof of runtime equivalence on Android hardware. The recovered source is not the lost original source, comments or original tests.
