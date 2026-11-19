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
