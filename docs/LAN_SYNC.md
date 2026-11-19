# Local Wi-Fi sync preview

Sahl Sync can pair Android with multiple Windows folders for manual sync. Use copies of your files while testing.

## On the PC

Use Python 3.11 or later and OpenSSL on PATH. Keep both companion files together and open the tray companion from Windows PowerShell.

```powershell
powershell.exe -NoProfile -STA -File .\desktop\Start-SahlSyncTray.ps1
```

Enter the PC Wi-Fi address and port, add your folders, and select **Start sharing**. Select a folder to copy its pairing code or show its QR. Enable **Start with Windows** to share after sign-in.

The command-line companion also works. The optional QR image requires `python -m pip install "qrcode[pil]"`. Get the PC's Wi-Fi IPv4 address from `ipconfig`.

```powershell
python desktop/sahl_sync_lan.py "C:\SyncTest" --share "C:\AnotherTest" --listen 192.168.1.10 --qr "$env:TEMP\sahl-pair.png"
```

Substitute your PC address and test folder. Keep that terminal open and allow incoming connections for Python on the private network if Windows prompts. Open the QR image or use the printed pairing code. Keep the code and image private.

## On Android

1. Open **Local Wi-Fi sync (preview)** in Sahl Sync.
2. Enter the PC's pairing code. If you use the optional QR, scan it with your phone’s camera or QR reader and paste the decoded text. Tap **Save** to verify the connection.
3. Choose a test folder on the phone, select a **Sync mode**, then tap **Sync now**.
4. Use **Add folder pair** for each additional share. Each pair has its own code, mode, result, and version history. **Sync all folders** runs the configured pairs one at a time.

Choose **Phone → PC**, **PC → Phone**, or **Two-way (newest wins)**. A one-way mode transfers only in the selected direction and skips a file when the destination has the newer copy. If timestamps match or are unavailable for files with different contents, Sahl Sync reports a conflict and preserves both. Update both the APK and Windows companion to use timestamp comparisons.

Before replacing a file on either device, Sahl Sync saves the old copy on the phone. Open **Version history** to export an older version as a separate file. Deletions are not propagated. Empty folders do not sync. The preview supports up to 224 MiB per file and 5,000 items per run.

The preview uses an encrypted connection checked against the PC certificate fingerprint from the pairing code. It is for private Wi-Fi. Transfers are manual. Select **Sync now** or **Sync all folders** after reconnecting to resume an interrupted file. Resume requires the updated Windows companion and matching file contents and destination version.
