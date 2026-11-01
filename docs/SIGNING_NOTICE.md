# 0.1.17 signing migration

Starting with 0.1.17, distributed APKs use a new fixed Filmroom release certificate. It cannot update the previous debug-signed packages in place. Uninstall/reinstall clears application-private edits, temporary photos and queued exports; files already saved to the system gallery/downloads remain. Subsequent packages signed with this same new key can update in place.

Public certificate SHA-256: `1cf7b23f5c954a781ef1260634fb8b4df3e84a59bae486dc5b357bd5fabaf37a`.

The private key, passwords and recovery material are not source files and must never be included in GitHub, release assets or source archives. This public certificate fingerprint is not secret. Gradle release output is unsigned; maintainers sign it separately using their securely held key. No key is embedded in build scripts.
