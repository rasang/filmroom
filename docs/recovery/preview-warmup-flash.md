# Preview preparation flash

The Activity drew the animation at `duration` while waiting for source video/audio
priming, then reset the clock to zero. This exposed the finished image before
starting the viewfinder animation, producing an end-to-start jump.

Preview-only fix: both bounded preparation paths clear the default framebuffer to
the page background rather than sampling either photo. Readiness still resets the
clock to zero and does not consume the 3000 ms animation. Idle pages still show
the finished photo; export and renderer shaders are untouched.

Validation:
- Host GLES2 landscape and portrait: all 330000 target pixels clear to #181818,
  alpha 255 after a completed-photo frame. `preview-flash-validation/result.json`.
- Activity integration contracts cover both warmup branches, full-target clear,
  zero-based playback after warmup and idle/manual replay behavior.
- No Android phone/emulator runtime verification.
