# Google Drive setup - required before live testing

Sahl Sync connects to Google Drive using OAuth 2.0 with PKCE (no client secret is stored in the app).

1. In [Google Cloud Console](https://console.cloud.google.com/), create or select a project and enable the **Google Drive API**.
2. Configure the **OAuth consent screen** and add the `https://www.googleapis.com/auth/drive` and `https://www.googleapis.com/auth/userinfo.email` scopes.
   - `drive` scope is required so users can browse and sync arbitrary folders in their Google Drive.
3. Create an **OAuth client ID** of type **Android**:
   - Package name: `com.aly.sahlsync` (or `com.aly.sahlsync.preview` for debug builds)
   - SHA-1 certificate fingerprint: your signing key's SHA-1.
4. Build with your Google OAuth client ID (and optional custom redirect scheme):

```sh
gradle assembleDebug \
  -PgoogleClientId=YOUR-CLIENT-ID.apps.googleusercontent.com \
  -PgoogleRedirectScheme=com.googleusercontent.apps.YOUR-CLIENT-ID
```

If `googleRedirectScheme` is omitted, it defaults to `com.aly.sahlsync`.
