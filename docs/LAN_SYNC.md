# Local Wi-Fi sync preview

Sahl Sync can pair Android with multiple Windows folders for manual sync. Use copies of your files while testing.

## On the PC

1. Download **SahlSyncPC.exe** from the latest [GitHub release](https://github.com/AliSherif1309/SahlSync/releases) and double-click it. Nothing else needs to be installed.
2. Check the **PC address** (it is filled in for you) and add the folders you want to share.
3. Select **Start sharing**. If Windows asks, allow access on **private networks**.
4. Select a folder to show its QR code. Tick **Start with Windows** to share automatically after sign-in.

Developers can still run `python desktop/sahl_sync_pc.py` (needs `cryptography` and `qrcode[pil]`) or the command-line `desktop/sahl_sync_lan.py`.

## On Android

1. Open **Local Wi-Fi sync (preview)** in Sahl Sync.
2. Tap **Scan QR from PC** and point the camera at the QR in the PC app (or tap **Enter pairing code** and paste it). Tap **Save** to verify the connection.
3. Choose a test folder on the phone, select a **Sync mode**, then tap **Sync now**.
4. Use **Add folder pair** for each additional share. Each pair has its own code, mode, result, and version history. **Sync all folders** runs the configured pairs one at a time.

Choose **Phone → PC**, **PC → Phone**, or **Two-way (newest wins)**. A one-way mode transfers only in the selected direction and skips a file when the destination has the newer copy. If timestamps match or are unavailable for files with different contents, Sahl Sync reports a conflict and preserves both. Update both the APK and Windows companion to use timestamp comparisons.

Before replacing a file on either device, Sahl Sync saves the old copy on the phone. Open **Version history** to export an older version as a separate file. Deletions are not propagated. Empty folders do not sync. The preview supports up to 2 GB per file and 20,000 items per run.

The preview uses an encrypted connection checked against the PC certificate fingerprint from the pairing code. It is for private Wi-Fi. Transfers are manual. Select **Sync now** or **Sync all folders** after reconnecting to resume an interrupted file. Resume requires the updated Windows companion and matching file contents and destination version.
