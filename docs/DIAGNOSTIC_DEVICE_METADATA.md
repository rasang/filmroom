# Device context in diagnostic shares

The beta39 report contained Android API, ABI and numeric runtime limits, but no manufacturer/model labels. Diagnostic shares now add four public product/OS labels:

- `deviceManufacturer`: `Build.MANUFACTURER`
- `deviceBrand`: `Build.BRAND`
- `deviceModel`: `Build.MODEL`
- `androidRelease`: `Build.VERSION.RELEASE` (Android API remains separately recorded)

These appear in the human-readable report header and additive JSON fields. They describe the device producing the report, not individual historical events. They do not identify a unique device. No serial number, Android ID, build fingerprint, account, user-assigned device name or location is added. Sharing remains user-initiated through the existing diagnostic share flow.

Labels are bounded to 128 characters, strip control/format characters and invalid UTF-16 surrogates, and use `unknown` when missing. JSON escaping remains applied separately. Existing event allowlists are unchanged.
