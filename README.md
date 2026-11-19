# Sahl Sync

Sahl Sync pairs Android folders with Microsoft OneDrive. It supports upload only, download only, and optional newest-wins two-way sync.

## Install and update

Download the signed APK from [GitHub Releases](https://github.com/AliSherif1309/SahlSync/releases). Preview builds are marked as pre-releases. In the app, tap **Check for updates** to list the newest published APK by Android version code and open its release page. Android requires matching package and signing certificates for an in-place update. If you installed a local test build signed with a different key, keep it installed until you have a migration plan for your settings.

## Features and limits

- Manual or scheduled runs with network and charging conditions, activity per folder, and optional completion notifications.
- OneDrive account selection for each folder pair. Work or school accounts may require organization approval.
- Resumable uploads for new files over 10 MiB. Existing-file replacements and downloads currently restart if interrupted.
- Two-way mode uses the newest modified copy and preserves prior versions. Tied or missing timestamps with different contents are reported as conflicts. Deletions are not propagated.
- **128 MiB maximum per file**, 5,000 items per run, and 32 levels of folder nesting. Oversized uploads can stop the current run. An oversized cloud-only download is reported as a conflict. Completed files remain available.
- Older OneDrive copies are stored under `.sahl-sync-history` and can be saved from **Version history**. There is no automatic cleanup.

## Build from source

Use JDK 17, Android SDK 36 and Gradle 9.5.0. The package is `com.aly.sahlsync`.

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

See [Microsoft setup](docs/MICROSOFT_SETUP.md) for account registration and local testing.

## Publish a release

Set these repository Actions secrets for the designated Sahl Sync signing key:

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Register the release certificate's Base64 SHA-1 hash for Android package `com.aly.sahlsync` in Microsoft Entra. If it differs from the local test registration, set the `MSAL_SIGNATURE` repository variable to the registered release hash. Set `MSAL_CLIENT_ID` if using a separate Microsoft application registration. The release job verifies the signing key and manifest before publication.

Bump both `versionName` and `versionCode` in `app/build.gradle`. Merging the version change to `main` builds, signs, verifies and publishes a numbered release with the APK and SHA-256 checksum. You can also run **Publish Sahl Sync release** manually from `main`. A previously published tag is left intact. Alpha and beta versions are published as pre-releases.
