package com.aly.sahlsync;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sahl Union door of Sahl Sync (only apps signed with the Sahl key can open it):
 *   content://com.sahl.sync.union/status -> pairs_count, signed_in, last_sync_at
 */
public class UnionProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context c = getContext();
        if (c == null) return null;
        String first = uri.getPathSegments().isEmpty() ? "" : uri.getPathSegments().get(0);
        if (!"status".equals(first)) return null;
        MatrixCursor out = new MatrixCursor(new String[]{"pairs_count", "signed_in", "last_sync_at"});
        try {
            JSONArray pairs = Store.pairs(c);
            long lastSync = 0L;
            boolean signedIn = false;
            for (int i = 0; i < pairs.length(); i++) {
                JSONObject p = pairs.optJSONObject(i);
                if (p != null) {
                    if (!p.optString("accountId", "").trim().isEmpty()) signedIn = true;
                    long t = p.optLong("lastRun", p.optLong("lastSync", 0L));
                    if (t > lastSync) lastSync = t;
                }
            }
            out.addRow(new Object[]{pairs.length(), signedIn ? 1 : 0, lastSync});
        } catch (Exception e) {
            out.addRow(new Object[]{0, 0, 0L});
        }
        return out;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
