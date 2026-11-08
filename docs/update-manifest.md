# Update manifest

Set `UpdateConfig.MANIFEST_URL` to the public COS HTTPS JSON URL. Empty means unconfigured and never requests a placeholder. No credentials belong in the URL. This change adds INTERNET permission; requests happen after tapping Settings → Check for updates, or once on cold startup subject to a persisted 12-hour cooldown. Photos remain on device.

```json
{
  "versionCode": 123,
  "versionName": "0.2.0",
  "releaseNotes": "- Improved previews\n- Fixed layout",
  "downloads": [
    {"name": "蓝奏云", "url": "https://wwbkn.lanzouc.com/i1n8q4ey9k6d"},
    {"name": "百度云", "url": "https://pan.baidu.com/s/1qCuWVmdU1bgtMCPp5hfyyw?pwd=2k8x"}
  ]
}
```

Version values above are examples, not a release declaration. Use the actual APK versionCode and versionName. versionCode is a positive integer up to 2147483647 and must be strictly greater than the installed package to trigger a dialog. versionName is display-only, so beta10 vs beta9 lexicographic ordering cannot interfere. releaseNotes is optional; leading `- `, `* `, `+ ` render as bullets, preserving line breaks. Other Markdown and HTML remain plain text and cannot fetch remote media or execute links.

Response is UTF-8 JSON, max 64 KiB. One to eight uniquely addressed downloads, name max 80 characters, versionName max 80, notes max 4000. URLs must be HTTPS DNS addresses (default/443), no credentials/fragments/IP literals/local domains. Browser handles download pages; the app neither downloads nor installs APKs. Labels show in full, with host separately. Clicking links does not dismiss the dialog; Close does. Website failures cannot be detected by the app, so users can return and try another mirror.

Use direct COS URL, no redirects. Serve Content-Type application/json; charset=utf-8. Only conditional ETag revalidation is cached. Every manual check contacts the server (or joins an already running check); a failure never masquerades as a fresh cached answer. Connection/read timeout 6s, bounded payload, overall check timeout 22s. No automatic retries, analytics, identifiers, or image upload. Keep cache headers short enough for release needs.

No COS bucket or public policy is configured by this implementation.

## Public endpoint verified 2026-11-08

Configured unsigned endpoint: https://filmroom-1305412660.cos.ap-guangzhou.myqcloud.com/filmroom-update.json

Direct GET returned HTTP 200, application/json, 350 bytes, ETag `"951a1e479ec958d6d58299d97ce37026"`. Conditional If-None-Match plus Cache-Control: no-cache returned 304 with matching ETag. The public JSON currently declares test versionCode 999, name 测试版 999, two download links. This triggers an update for code 62; it is not an actual 999 APK release verification.

COS returns Content-Disposition: attachment and x-cos-force-download: true. These are browser download hints and do not affect our HttpURLConnection response-body parsing. JSON is never opened in the browser; only the mirror links are. No APK is hosted on the COS endpoint by this change.

The response currently has no Cache-Control header. Recommended object metadata is Cache-Control: public, max-age=60 and Content-Type: application/json. Server metadata was not changed. 60 seconds is a freshness window, not a guaranteed propagation deadline. Client bypasses local response caching and revalidates online with ETag; real-device and CDN propagation after publishing a changed JSON still need verification.



## Cold-start checks

MainActivity considers an automatic check once per process, when first resumed. Immediately before an eligible automatic fetch, its time is synchronously persisted; failed/offline checks also consume the 12-hour window. Manual checks ignore that window. Concurrent manual callers join the same bounded application-scoped request, which retains no Activity. One result can be presented only once; later manual requests can show the version again.

Same-boot cooldown uses elapsed realtime, unaffected by wall-clock edits. Across reboot it uses wall time; backward jumps conservatively rebase a full cooldown rather than repeatedly requesting or waiting forever. Automatic errors and up-to-date results are silent. New-version UI awaits a resumed, live Activity and uses the same link dialog. Destruction cancels only the UI waiter and dismisses its dialog, not another screen's request. No background polling or scheduler is installed.

## Manual success window and recreation

Manual checks reuse a validated successful manual response for 10 minutes from the underlying request's start, including both update-available and no-update outcomes. Reuse shows the normal result with no throttling notice. Failures are not cached and can be retried immediately. Concurrent callers still join one network request; a manual participant joining an automatic fetch can reuse that success later. The manual success window is independent of the persisted 12-hour automatic-attempt cooldown.

Success data and timestamps are persisted, so restarting the process does not bypass the 10-minute window. Same-boot age uses elapsed realtime; across reboot it uses wall time, with backwards clock changes conservatively rebasing one fresh window. Expired or invalid cached data triggers a normal request. Storage failure does not turn a successful response into a visible network failure.

A recreated startup Activity joins the same startup Deferred, even when it completed while no owner was alive. It does not re-fetch or consume a second automatic cooldown. Existing per-response presentation claiming prevents duplicate popups from multiple observers.
