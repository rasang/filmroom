# Xiaomi DNG compatibility boundary

DefaultBlackRender=None (LONG/count 1 in IFD0) is a generic supported render hint, not permission to discard sensor BlackLevel. Auto, unknown values, malformed type/count and raw-subIFD override remain rejected.

The Xiaomi brand adapter opts into shared List2 CFA GainMap execution only for a complete supported four-phase, one-plane, pitch-2 map set on full active bounds. Maps are applied after linear reference normalization and before demosaic using global pixel centers; values are clipped to the stage range before and after each operation. Finite-value, payload-size, memory, cancellation and required-opcode guards remain. No model-specific branch or blanket brand approval is used. Non-Xiaomi optional-map handling remains unchanged.

Validation includes mathematical/ordering/boundary tests, actual complete RAW decoding under two strip budgets, prior RAW byte regressions and production Repository/host JNI import/reopen/cleanup. These are not phone runtime tests or a camera color calibration guarantee. Actual user files are supplied externally through test environment variables and are excluded from distributed source.
