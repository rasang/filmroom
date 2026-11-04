# v4 lower-rail reconstruction

This is an authored, finite reference-guided reconstruction, not a measured whole-roll factory replica.

The compiler makes a reusable arrow/digit atom bank from byte-pinned existing authored PNGs, clears each affected lower rail, and lays out all marks using explicit stock-specific coordinate models. The upper rail is frozen byte-for-byte. Font style, color, height and complete antialias footprints are preserved. Number placement uses visible ink-left and one stock-wide arrow-tip gap, not a variable glyph advance-box center. Absolute coordinates are rounded independently; no repeated rounding or ad hoc translation list is used.

Portra400, Ektar100 and E100 each use their own fitted three-arrow short/long/medium unit. Independent aperture and top-rail geometry do not explain away the observed nonuniformity. Gold200 uses only its finite observed early-roll sequence; a separate Gold scan corroborates the same nonuniform order. Source URLs, coordinates, fit residuals and approximate metric uncertainty are recorded in bottom-models.json. Do not extrapolate these finite observations to unknown sections or other stocks.

Portra800 uses an authored midpoint lattice consistent with same-stock reference triplets. The absolute31.25mm pitch is not measured on film; its6x17 window is pixel-identical to the previous midpoint review option. This is not an additional visible6x17 repair. Portra160, Provia100F, Velvia100 and Pro400H retain their existing PNG bytes and versions.

Run rebuild_bottom_rails.py with --asset-root and --output. Run test_bottom_models.py for positive/negative formula consistency tests. The compiler independently checks redundant placement coordinates against the periodic/finite model, cycle-step summaries, unique number vocabulary, upper-rail identity, full-alpha opacity, glyph collisions and finite crop coverage. Never replace a definition used by frozen queue snapshots: append selected v4 assets and preserve all prior published v1 and integrated v2 assets/definitions.

Only authored PNGs, numeric coordinate data, public source URLs and scripts belong in source. Reference photographs, user screenshots, private research paths and review documents are not production assets.
