# Sahl Sync

Sahl Sync keeps Android folders in sync with Microsoft OneDrive and with your Windows PC over Wi-Fi. Each folder can be upload only, download only, or two-way (newest wins, deletions included).

## Install and update

Download the signed APK from [GitHub Releases](https://github.com/AliSherif1309/SahlSync/releases). In the app, tap **Check for updates** to open the newest release. If you’re switching from a locally signed test build, save your settings before reinstalling.

## Features and limits

- Manual or scheduled runs with network and charging conditions, activity per folder, and optional completion notifications.
- OneDrive account selection for each folder pair. Work or school accounts may require organization approval.
- Large files (over 10 MiB) upload in chunks, both new files and replacements. New-file uploads resume after an interruption.
- Two-way mode uses the newest modified copy and preserves prior versions. Tied or missing timestamps with different contents are reported as conflicts. Deletions sync both ways after a file has synced once: a file deleted on the phone is moved to OneDrive version history, and a file deleted on OneDrive is renamed on the phone to `sahl-sync-previous-…` (nothing is erased). If a file was edited on one side and deleted on the other, the edit wins and the file is restored. Unchanged files are skipped without re-reading them.
- **2 GB maximum per file**, 100,000 items per run (20,000 for Wi-Fi sync), and 32 levels of folder nesting. Problem items (oversized files, names that differ only by capital letters, too-deep folders) are skipped and listed in Backup activity while the rest of the run continues.
- Files are compared with OneDrive's own fingerprint (QuickXorHash), so matching files are not downloaded just to check them.
- Schedules: once, daily, selected weekdays, every hour or every 6 hours.
- Replaced and deleted files are stored under `.sahl-sync-history` and can be saved from **Version history**. The newest 10 copies per file are kept; older ones are removed automatically.

## Phone ↔ PC over Wi-Fi

Download **SahlSyncPC.exe** from Releases, add folders, select **Start sharing**, then in the phone app tap **Local Wi-Fi sync > Scan QR from PC**. See [Wi-Fi sync](docs/LAN_SYNC.md).

## Editing a backup

**Edit backup settings** can change the phone folder and the OneDrive folder. After a change the next sync compares both folders fresh and deletes nothing on that run.

## Build from source

Use JDK 17, Android SDK 36 and Gradle 9.5.0.

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

See [Microsoft setup](docs/MICROSOFT_SETUP.md) for account registration and local testing.
