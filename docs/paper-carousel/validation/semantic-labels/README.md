# Semantic paper labels, 320dp / 2026-11-07

User correction: previous carousel model names broke at unnatural places.

- Separate brand / identifying model words / medium type. Omit repetitive Professional only from compact card; accessibility retains full original name.
- Keep 12sp. Cards start at136dp and grow to measured semantic line width (no font shrinking). Disable automatic hyphenation. Film carousel unchanged.
- English compact types use B&W paper / B&W print film / Color print film; full labels remain in accessibility.
- Japanese and Korean long print-film categories split at semantic word groups, not arbitrary characters.
- Current full production Kotlin compiled successfully. Targeted host suite: 9 tests passed; actual activity at320dp tests all10 media in4 locales, checking exact expected line count/no ellipsis/no clipped line, selection/scroll/direct-disabled behavior. Chinese and Korean screenshots inspected.
- Initial run caught Korean extra wrapping. Fixed by explicit semantic break, then rebuilt all production Kotlin and reran all9 tests successfully.
- No APK/new native code. Existing none integration requirements and historical print-can label caveats remain.
