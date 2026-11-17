# Microsoft setup - required before live testing

An app-specific Microsoft Entra registration is required. Do not use OneSync's
client id, another app's registration or any client secret in this Android app.

1. Create **Sahl Sync** in Microsoft Entra App registrations. Support personal
   Microsoft accounts (and organizational accounts if desired).
2. Add Android platform with package `com.aly.sahlsync` and the SHA-1 Base64
   signature hash for the exact APK signing certificate. Development and final
   release certificates need separate redirect registrations.
3. Add delegated Microsoft Graph `Files.ReadWrite`. The app needs this scope to
   let the user select arbitrary folders in their own drive. App-folder-only
   permission cannot replace that folder selection behavior.
4. Build with the public application/client ID and raw Base64 signature hash:

```sh
gradle assembleDebug -PmsalClientId=YOUR-APPLICATION-ID -PmsalSignature=YOUR-BASE64-SIGNATURE-HASH
```

MSAL config uses `msauth://com.aly.sahlsync/<URL-encoded hash>`; the manifest uses
the raw decoded hash path. Never enter a Microsoft password or client secret in
the project or send one through chat. A public client ID is configuration, not a
password. Keep the signing private key protected.

Use a disposable source folder and a separate empty OneDrive folder for first
live testing. Verify nested folders, repeated backups, changed files, permission
revocation, account changes and interruption before trusting valuable data.

References:
- https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-mobile-app-android-sign-in
- https://learn.microsoft.com/en-us/entra/msal/android/msal-configuration
- https://learn.microsoft.com/en-us/graph/api/driveitem-put-content
- https://learn.microsoft.com/en-us/graph/api/resources/driveitem

## Registered local test build

The `OneDrive local test APK` workflow creates an unsigned APK using the Sahl Sync
Microsoft registration and the registered local development certificate hash.
These are public application identifiers, not credentials. The artifact cannot
be installed until signed locally with the matching development key. The key
stays on the developer laptop and is never sent to GitHub.

Set JAVA_HOME to your JDK, then run `tools/Sign-LocalTestApk.ps1` with the downloaded
unsigned APK and a new output APK path. The script checks the local certificate
against the registered hash, signs the APK, verifies its signature and prints its
SHA-256 checksum. Never install an APK with a mismatched Microsoft callback hash.
Production signing and a production redirect registration are separate work.
