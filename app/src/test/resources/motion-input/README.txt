Generated synthetic fixture, no user content or external media.
video.mp4: ffmpeg testsrc2=size=64x48:rate=10, duration 0.5s, libx264/yuv420p.
live.jpg: ffmpeg red 64x48 JPEG with XMP MotionPhoto=1 and exact appended-video offset, followed by video.mp4 bytes.
Covers Android ContentResolver file URI -> bounded copy -> JPEG/XMP inspection -> MP4 validation -> extraction -> retained output lifetime.
The MP4 is actually decodable; this is not only a mock box-tree fixture.
