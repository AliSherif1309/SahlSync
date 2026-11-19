## 1.2.0
- New: Messages & apps backup (replaces SMS Import/Export and App List Backup).
  - Back up SMS, MMS with attachments and the call log to one zip; restore without duplicates.
  - Back up the list of installed apps (JSON + readable HTML page), optionally with APK files; reinstall missing apps from the list.
  - Automatic daily, weekly or monthly backup into any folder, so Sahl Sync can carry it to OneDrive or the PC.

## 1.1.2
- Wi-Fi Sync now runs as a background job: leave the app and it keeps going, with progress and a Stop button in the notification.

## 1.1.1
- Remove a folder from Sahl Sync (OneDrive and PC folders) without deleting any files.
- Wi-Fi sync shows live progress: a progress bar for files, MB counter for big files, the file being checked, and a Stop button.

## 1.1.0
- Arabic interface (Settings > Language: Automatic, English, العربية) with right-to-left layout and the Thmanyah Sans font.
- Deleting a whole folder now syncs everywhere: OneDrive ↔ phone (files kept in Version history) and phone ↔ PC (empty folders removed on both sides).
- PC app lives in the system tray: closing the window keeps sharing; tray menu to open, start/stop sharing and quit.

## 1.0.0
- First stable release.
- Wi-Fi sync with your PC: deletion sync (with recovery copies on both sides), background Auto sync, and the phone finds the PC automatically when its address changes.
- Faster: phone and PC remember file fingerprints, so unchanged files are not re-read; bigger transfer buffers; fewer full re-reads before uploads.
- New options per folder: sync deletions on/off, how many older copies to keep, notifications on/off, every-15-minutes schedule.
- Cleaner design: one main action per screen, outlined secondary buttons, folder cards, simpler wording.

## 0.9.0-beta.1
- Edit backup settings can change the phone folder and the OneDrive folder (sync records reset safely).
- New Windows app SahlSyncPC.exe: no Python or OpenSSL needed; shows a QR code per shared folder; start with Windows.
- Phone: Scan QR from PC to pair.

## 0.8.0-beta.1
- OneDrive files are compared using OneDrive's fingerprint, so matching files are no longer downloaded just to check them.
- Files up to 2 GB; large replacements upload in chunks.
- Problem items are skipped and reported instead of stopping the whole run; 100,000 items per run.
- New schedules: every hour and every 6 hours.
- Version history keeps the newest 10 copies per file and cleans up older ones.
- Files deleted on OneDrive are also saved to Version history before the phone copy is moved aside.

## 0.7.0-alpha.4
- Two-way sync now syncs deletions; unchanged files are skipped without re-reading.

## 0.3.0-alpha1
- Live manual/daily work status and per-file counters; history updates automatically.
- Edit backup name, exclusions, network, charging and daily schedule without recreating the pair.
- Cancel a pair's queued/running backup and disable daily scheduling; accepted cloud requests may finish.
- File-specific conflict history, retry hints and cooperative cancellation during streaming and before cloud mutations.
- Settings apply to subsequent runs. Cancel disables daily backup; enable it again in Edit backup settings.

# Changes

## 0.2.0-alpha1

- Keep a single current file under its original name.
- Store verified app-managed snapshots before conditional updates.
- Add Version history browsing and verified save-as-copy to the phone.
- Share backup/version policy through a provider-independent backend contract.
- Preserve externally changed cloud files as conflicts and leave old alpha copies untouched.
- Wire account-state controls so Connect waits for initialization and reflects Connected.


## 0.1.0-alpha2

- Verify remote bytes before reporting a conflict when OneDrive metadata tags change.
- Recover unchanged files from older backup receipts without overwriting cloud copies.
- Keep token authorization on the initial Graph content request only; never forward it to download redirects.
- Add regression checks for metadata changes, altered content, missing receipts, failed downloads and redirect handling.
- Disable Connect while Microsoft sign-in initializes, and show Connected after account loading.

## 0.1.0-alpha1

- First upload-only OneDrive backup preview with local development signing.
