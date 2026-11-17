# Sahl Sync

Android folder backup with app-managed version history. Part of the Sahl suite.

**0.2.0-alpha1 - development preview. Keep OneSync until real-device validation is complete.**

## File behavior

- Each source file uses its original name in the backup folder: `report.txt`.
- Changed content updates the same cloud item rather than creating hash-named working files.
- Before an update, Sahl Sync downloads and verifies the previous cloud contents,
  stores a separate snapshot, verifies that snapshot, then conditionally updates
  the current item. Failed archiving prevents replacement.
- Unchanged files do not create more snapshots. An independently changed cloud
  file is reported as a conflict. Conditional writes protect against changes
  between checking and updating.
- History is made of Sahl-managed files under `.sahl-sync-history/<pair>/<file>/`,
  with `origin.json` metadata. It does not call OneDrive's native version-history API.
- **Version history** on each backup lets you choose a file, browse older copies,
  and save a verified copy to your phone with Android's document picker. This
  first history UI saves a copy; it does not overwrite the phone's current file.
- Snapshots have no automatic expiry or cleanup in this preview. They consume
  OneDrive quota and remain dependent on that storage existing and being accessible.
- Previous hash-named alpha test copies are left untouched. Their contents are
  not automatically migrated or deleted. New original-name receipts start with
  the next backup; the following edit creates the first app-managed snapshot.

The `VersionedBackup.Backend` contract is provider-independent so the same
archive-before-update behavior can be used for future local/device sync backends.
Only the OneDrive adapter exists today; this is not yet a Syncthing replacement.

## Current features and limits

Microsoft MSAL sign-in, persistent read-only local folder access, OneDrive folder
browser, manual or approximate daily uploads, unmetered/charging/battery rules,
exclusion globs, backup history, and the shared green/gold Sahl theme.

20 MiB per file; 5,000 visited items and 32 nested folders per run. The reserved
`.sahl-sync-history` folder is excluded from source traversal. No deletion sync,
large/resumable transfers, two-way sync, in-place restore, retention settings or
Arabic UI yet. WorkManager may interrupt long runs; completed uploads and
snapshots remain. History browsing currently uses the saved pair/file index;
metadata and snapshots remain in cloud storage if local settings are lost, but
an automatic history-import UI is not implemented yet.

## Build and testing

JDK 17, Android SDK 36, Gradle 9.5.0. CI runs unit tests, lint and APK compilation.
See [Microsoft setup](docs/MICROSOFT_SETUP.md) for the registered local test build.
App id: `com.aly.sahlsync`.

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

Real-device testing of earlier previews verified sign-in, first upload, unchanged
skips and separate edited versions. This preview needs a clean-name update,
archive browsing, saved-copy verification and cloud-edit conflict test on-device.

MSAL owns credentials; no passwords/tokens are logged. No analytics backend.
Device-local account/folder state is excluded from Android cloud backup.
[Shared design rules](docs/DESIGN.md) apply to future screens.
