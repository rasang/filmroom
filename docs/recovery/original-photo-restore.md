# Restored original photo access

This patch keeps backup recovery small and does not silently bundle media.

- A restored original URI is tested under the current installation's real permission state.
- For Android's media/document providers only, the platform `MediaStore.getMediaUri` API may supply an equivalent address. The candidate must be a media image/item URI and must actually be readable. No display-name search, guessed picker ID, cloud URL, or private path is used.
- Verified provider-equivalent addresses update the export record using its compare-and-set operation. Current and adjacent viewfinder pages use that address for both original pixels and Live video input.
- The missing-original dialog offers an explicit photo permission request and retry, alongside the existing OpenDocument reselection and durable relink flow. Full/partial permissions are not assumed to recreate picker grants.
- New backups preserve typed crop, border, presentation parameters, and capture metadata. The snapshot parsers enforce geometry invariants. GPS and diagnostic warning text are excluded. Old backups without these optional fields remain supported; they cannot retroactively supply omitted composition/metadata.
- Media streams are not rewritten; embedded video/audio remain intact. This is not full media backup. If an old picker grant has vanished and Android cannot map it, the user must select the original again. If the original has been deleted, a metadata-only backup cannot restore it.

Verification covers readable originals, denied grants, certified mapping, unreadable equivalents, untrusted redirects, old/new backup records and geometry. Android provider behavior after real uninstall remains unverified without a connected phone.
