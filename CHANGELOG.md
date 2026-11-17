# Changes

## 0.1.0-alpha2

- Verify remote bytes before reporting a conflict when OneDrive metadata tags change.
- Recover unchanged files from older backup receipts without overwriting cloud copies.
- Keep token authorization on the initial Graph content request only; never forward it to download redirects.
- Add regression checks for metadata changes, altered content, missing receipts, failed downloads and redirect handling.
- Disable Connect while Microsoft sign-in initializes, and show Connected after account loading.

## 0.1.0-alpha1

- First upload-only OneDrive backup preview with local development signing.
