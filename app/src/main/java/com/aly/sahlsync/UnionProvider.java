package com.aly.sahlsync;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sahl Union door of Sahl Sync (only apps signed with the Sahl key can open it):
 *   content://com.sahl.sync.union/status -> pairs_count, signed_in, last_sync_at
 */
public class UnionProvider extends ContentProvider {
 @Override
 public boolean onCreate(){return true;}

 @Override
 public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
  Context c=getContext();
  if(c==null)return null;
  String first=uri.getPathSegments().isEmpty()?"":uri.getPathSegments().get(0);
  if(!"status".equals(first))return null;
  MatrixCursor out=new MatrixCursor(new String[]{"pairs_count","signed_in","last_sync_at"});
  try{
   List<SyncPair> pairs=Store.syncPairs(c);
   boolean signedIn=!GoogleAuth.accounts(c).isEmpty();
   for(SyncPair p:pairs){
    if(!p.account.trim().isEmpty()){signedIn=true;break;}
   }
   long lastSync=0L;
   JSONArray recent=Store.historyList(c,null,1);
   if(recent.length()>0){
    JSONObject top=recent.optJSONObject(0);
    if(top!=null)lastSync=top.optLong("time",0L);
   }
   out.addRow(new Object[]{pairs.size(),signedIn?1:0,lastSync});
  }catch(Exception e){
   out.addRow(new Object[]{0,0,0L});
  }
  return out;
 }

 @Override public String getType(Uri uri){return null;}
 @Override public Uri insert(Uri uri,ContentValues values){return null;}
 @Override public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
}
