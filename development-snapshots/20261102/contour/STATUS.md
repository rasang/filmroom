# Contour preview v3 source backup (unmerged / unvalidated)
Baseline Beta4: 47ef9f80d70e15cc7ca0fd664d2dc8eac1ce64dc.
User approved intermediate contour look, not release. App repository remains unchanged.
Patch includes 3 production overlays + portable contour and Activity loading tests.
Latest changes: 480 bounded intake copy; 4MiB byte LRU; predecode hit; separate computation/cache locks; epoch captured BEFORE waiting for computation lock, checked after acquisition and before publication; shared bitmaps never recycled on eviction; cancelled unpublished output recycled; 6MiB admission.
Static independent review clear after queued-miss trim fix. V3 has NOT been compiled or tested; v2 compile passed before new contour tests were added. Test evidence/parity execution pending serial window.
Does not change scientific pipeline, stage transitions, app version, SDK/GPU code. No package.
External public-photo parity harness deliberately excluded from repository patch (uses absolute evidence paths and Java prototype). Source-only harness preserved separately in this backup for later validation; no pictures or logs included.
