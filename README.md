# Sahl Sync

Sahl Sync pairs Android folders with Microsoft OneDrive. It supports upload only, download only, and optional newest-wins two-way sync.

## Install and update

Download the signed APK from [GitHub Releases](https://github.com/AliSherif1309/SahlSync/releases). Preview builds are marked as pre-releases. In the app, tap **Check for updates** to open the newest release. If you’re switching from a locally signed test build, save your settings before reinstalling.

## Features and limits

- Manual or scheduled runs with network and charging conditions, activity per folder, and optional completion notifications.
- OneDrive account selection for each folder pair. Work or school accounts may require organization approval.
- Resumable uploads for new files over 10 MiB. Existing-file replacements and downloads currently restart if interrupted.
- Two-way mode uses the newest modified copy and preserves prior versions. Tied or missing timestamps with different contents are reported as conflicts. Deletions are not propagated.
- **224 MiB maximum per file**, 5,000 items per run, and 32 levels of folder nesting. Oversized files are skipped and listed in Backup activity while the rest of the run continues.
- Older OneDrive copies are stored under `.sahl-sync-history` and can be saved from **Version history**. There is no automatic cleanup.

## Build from source

Use JDK 17, Android SDK 36 and Gradle 9.5.0.

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

See [Microsoft setup](docs/MICROSOFT_SETUP.md) for account registration and local testing.
