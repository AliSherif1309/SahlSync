# Sahl Sync

OneDrive folder backup for Android, part of the Sahl app suite.

**0.1.0-alpha2 — engineering preview. Not ready to replace OneSync.** A local test registration has verified Microsoft sign-in and one real upload.
Repeat-upload verification is being corrected; this remains a development build.

## Implemented first slice

- Microsoft MSAL single-account sign-in integration (configuration gated).
- Android folder picker with persistent read-only access.
- OneDrive folder browser and multiple folder backups, bound to the selected account.
- Manual or approximate daily upload-only backup through WorkManager.
- Unmetered-network, charging and battery-not-low constraints.
- One exclusion glob per line (`*.tmp`, `private/*`), relative to the selected folder.
- Separate content-addressed versions: `report--<sha256>.pdf`. Original filenames
  are retained as prefixes; no files are deleted. Existing cloud names use
  conflict-fail behavior. Known versions skip downloads when their saved ETag
  and size match. If a tag changes or a receipt is missing, verify remote SHA-256
  content before calling the file unchanged. Different bytes remain conflicts.
- Bounded run history, pause/resume scheduling, Microsoft sign-out.
- Shared Sahl colors, gradient, rounded panels and gold controls.

Limits: 20 MiB per file, 5,000 visited items and 32 nested folders per run. File
bytes are staged in private cache for consistent hashing/upload, then removed.
WorkManager can stop a run or delay it under Android battery restrictions. This
alpha has no foreground long-running transfer mode, persistent upload-session
resume, per-file conflict UI, retention cleanup or notification progress. Large
folders may need repeated runs. Completed versions remain in OneDrive.

## Setup and build

See [Microsoft setup](docs/MICROSOFT_SETUP.md). JDK 17, Android SDK 36 and Gradle
9.5.0 are required. CI runs unit tests, lint and a debug APK build. Download its
artifact from Actions for engineering inspection, not production installation.

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

App id: `com.aly.sahlsync`. There is no backend, analytics service or client secret.
MSAL owns token storage; credentials are never written into sync preferences or
history. Android backup is disabled for device-local account and folder state.

## Roadmap

1. Configure Microsoft registration and test consent, refresh, sign-out and
   account switching on a real device using a disposable test folder.
2. Add instrumented UI/provider tests, mock Graph tests and network interruption,
   throttling, conflict and storage-failure tests before production release.
3. Add large-file upload sessions, resumable jobs, per-file progress, richer
   conflict handling and restore/version browsing.
4. Add download-only and two-way sync after recovery tests pass.
5. Arabic UI with licensed bundled Thmanyah typography; keep fonts consistent
   with the other Sahl apps. English currently uses the system sans font.

[Shared Sahl design rules](docs/DESIGN.md) apply to every subsequent screen.
