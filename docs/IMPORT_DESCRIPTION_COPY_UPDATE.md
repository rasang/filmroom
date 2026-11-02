# Import description copy update

User-authorized exact Chinese: `从文件选择照片/RAW`.

Only resource `text_85534f27f5d9`, the second entry of MainActivity.importDialog, changed. The gallery entry and picker implementation are unchanged.

- Default / zh / ChineseStrings fallback: 从文件选择照片/RAW
- en: Choose a photo/RAW from files
- ja: ファイルから写真／RAWを選択
- ko: 파일에서 사진/RAW 선택

Validation: five XML parses and `git diff --check` passed. `ImportDescriptionHostTest.fourLocalesShowPhotoRawDescriptionAndKeepDocumentPickerMimeContract` passed on API35 for all four locales, testing the actual MainActivity dialog and confirming the second action still launches ACTION_OPEN_DOCUMENT with `image/*` and `application/octet-stream`. No photo is imported until a selection result. Fresh focused Gradle run: BUILD SUCCESSFUL in42s, 2026-11-02 03:17 UTC. Log `/workspace/shared/filmroom-import-description-tests.log`.

This is a generic source-picker description, not a guarantee that every camera's RAW format decodes. No format support, MIME filter, permission, version, APK or publication changed.
