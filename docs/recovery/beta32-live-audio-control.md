# beta32 Live audio control

Version 84 / 0.1.32-beta32, based on beta31.

The Live original-audio switch is shown only after the current viewfinder input has a successfully extracted motion clip. Ordinary photos, loading/unknown input, failed extraction and cleared previews hide it. Switching between Live and ordinary photos keeps the user's stored audio choice without exposing the control on ordinary input.

Quality and Live audio now share the same responsive action row. The switch is inside the whole-button touch target; tapping it toggles directly without a dialog. At narrow widths or large accessibility fonts the complete controls may wrap to avoid overlap. Export and playback semantics from beta31 are unchanged.

Review covered prepared entry, fresh load, page commit, clear/reselection and failed-load paths. Host tests cover visibility transitions, retained mute selection, same-row normal-width layout, narrow/large-font bounds and direct single-toggle clicks. No emulator or Android device validation was performed. The previous all-class suite limitations remain; the release check uses the affected/previous regression set and required preset/backup gate, not an all-class pass.

Final validation: 328 affected/previous regression tests and 122 preset/backup gate tests passed, with zero failures/errors/skips. Release lint completed with 0 errors and 105 warnings. Release assembly passed. The normal-width host-rendered row screenshot was inspected and shows the quality button and embedded Live switch side by side.
