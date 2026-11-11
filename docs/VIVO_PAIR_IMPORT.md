# vivo paired original Live import

The original image-only Photo Picker grant does not grant access to a companion MP4. The viewfinder requests video read access only after recognizing a paired Live image whose video was not found. It does not derive filesystem paths or construct guessed picker URIs.

- Existing authorized pair links are reused, but revalidated on every prepare.
- Android 10–12 requests READ_EXTERNAL_STORAGE; Android 13 requests READ_MEDIA_VIDEO; Android 14+ requests READ_MEDIA_VIDEO with READ_MEDIA_VISUAL_USER_SELECTED. Full and selected-only access are distinguished; selected access never grants all videos. A denied prompt is not repeated in the same screen session, including recreation. Returning from settings rechecks changed access; with selected-only access, an unresolved pair is retried once per actual resume because the selected set may change without changing permission bits. Loading itself does not trigger this retry.
- With granted video access, direct MediaStore images use volume, relative path and basename. Picker/document images expose only their authorized DISPLAY_NAME: candidates are queried by that basename in the accessible video collection. Names narrow the search; they never prove a pair. Cloud/provider-renamed images or videos absent from MediaStore may still require explicit video selection.
- JPEG and terminal MP4 vivo extension must have the same binding ID and a supported version tuple: 2014/2014, 2104/2104, or 2200/2016. Wrong IDs, unsupported tuples, malformed or duplicate records and multiple valid candidates are rejected.
- After permission refusal, or when a permitted search still finds no matching MP4, the viewfinder page offers “Select original Live video”. The video document picker provides a user-selected read grant. Only after validation is the URI linked privately to the original image. Persistent grants are retained when provided; otherwise access remains session-only and a warning is shown.
- A known unpaired input blocks video/Live export with a selection prompt instead of silently producing a still-only effect. Cancellation does not change the existing link. Revoked or changed links require re-selection.
- Cached extracted media is used by both viewfinder preview and export. Adjacent caches are invalidated after association.
- Light-table mode retains its existing behavior: it does not play the source Live video.

The report that prompted this fix was truncated and did not establish whether the user's source was paired. The implementation fixes a verified import gap; it does not claim a successful reproduction or real-device gallery test for that particular photo.

The pairing URI is stored only in app-private preferences under a hashed image-URI key. Diagnostics record PAIR_REQUIRED and byte counts, not source URIs, filenames or binding IDs.

Permission reference: https://developer.android.com/about/versions/14/changes/partial-photo-video-access . A Photo Picker URI grant and Selected Photos Access are different scopes; neither authorizes an unselected companion video automatically.
