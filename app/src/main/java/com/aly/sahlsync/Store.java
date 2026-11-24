package com.aly.sahlsync;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class Store {
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("sync",Context.MODE_PRIVATE);}
 static synchronized List<SyncPair> syncPairs(Context c)throws Exception{
  List<SyncPair> list=new ArrayList<>();
  try(android.database.Cursor cursor=db(c).rawQuery("SELECT json FROM pairs ORDER BY sort_order ASC",null)){
   while(cursor.moveToNext())list.add(SyncPair.fromJson(new JSONObject(cursor.getString(0))));
  }
  return list;
 }
 static synchronized JSONArray pairs(Context c)throws Exception{
  JSONArray all=new JSONArray();
  try(android.database.Cursor cursor=db(c).rawQuery("SELECT json FROM pairs ORDER BY sort_order ASC",null)){
   while(cursor.moveToNext())all.put(new JSONObject(cursor.getString(0)));
  }
  return all;
 }
 static synchronized void add(Context c,JSONObject pair)throws Exception{
  SyncPair typed=SyncPair.fromJson(pair);
  android.database.sqlite.SQLiteDatabase database=db(c);
  long nextOrder=0;
  try(android.database.Cursor cursor=database.rawQuery("SELECT COALESCE(MAX(sort_order),-1)+1 FROM pairs",null)){
   if(cursor.moveToFirst())nextOrder=cursor.getLong(0);
  }
  android.content.ContentValues row=new android.content.ContentValues();
  row.put("id",typed.id);row.put("provider",typed.provider.id);
  row.put("json",pair.toString());row.put("sort_order",nextOrder);
  if(database.insertOrThrow("pairs",null,row)<0)throw new java.io.IOException("Could not save folder pair");
 }
 static synchronized void update(Context c,JSONObject pair)throws Exception{
  SyncPair typed=SyncPair.fromJson(pair);
  android.content.ContentValues row=new android.content.ContentValues();
  row.put("provider",typed.provider.id);row.put("json",pair.toString());
  if(db(c).update("pairs",row,"id=?",new String[]{typed.id})<=0)
   throw new java.io.IOException("Folder pair not found");
 }
 static synchronized void remove(Context c,String id)throws Exception{
  android.database.sqlite.SQLiteDatabase database=db(c);
  database.beginTransaction();
  try{
   database.delete("pairs","id=?",new String[]{id});
   database.delete("history","pair_id=?",new String[]{id});
   database.setTransactionSuccessful();
  }finally{database.endTransaction();}
  forgetAll(c,id);
 }
 static synchronized JSONObject pair(Context c,String id)throws Exception{
  try(android.database.Cursor cursor=db(c).rawQuery("SELECT json FROM pairs WHERE id=?",new String[]{id})){
   if(cursor.moveToFirst())return new JSONObject(cursor.getString(0));
  }
  throw new java.io.IOException("Folder pair not found");
 }
 private static final class ReceiptsDb extends android.database.sqlite.SQLiteOpenHelper {
  ReceiptsDb(Context c){super(c.getApplicationContext(),"sahl_sync.db",null,3);}
  @Override public void onConfigure(android.database.sqlite.SQLiteDatabase db){db.enableWriteAheadLogging();}
  @Override public void onCreate(android.database.sqlite.SQLiteDatabase db){
   db.execSQL("CREATE TABLE IF NOT EXISTS receipts("
    +"pair TEXT NOT NULL,path TEXT NOT NULL,name TEXT NOT NULL,"
    +"remote_id TEXT NOT NULL,tag TEXT NOT NULL,hash TEXT NOT NULL,"
    +"size INTEGER NOT NULL,phone_modified INTEGER NOT NULL,PRIMARY KEY(pair,path))");
   db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipts_pair ON receipts(pair)");
   createPairsAndHistory(db);
   createLanFiles(db);
  }
  @Override public void onUpgrade(android.database.sqlite.SQLiteDatabase db,int oldVersion,int newVersion){
   if(oldVersion<2)createPairsAndHistory(db);
   if(oldVersion<3)createLanFiles(db);
  }
  private static void createPairsAndHistory(android.database.sqlite.SQLiteDatabase db){
   db.execSQL("CREATE TABLE IF NOT EXISTS pairs("
    +"id TEXT PRIMARY KEY,provider TEXT NOT NULL,json TEXT NOT NULL,sort_order INTEGER NOT NULL)");
   db.execSQL("CREATE TABLE IF NOT EXISTS history("
    +"id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,pair_id TEXT,message TEXT NOT NULL)");
   db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_pair_time ON history(pair_id,time DESC)");
   db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON history(time DESC)");
  }
  private static void createLanFiles(android.database.sqlite.SQLiteDatabase db){
   db.execSQL("CREATE TABLE IF NOT EXISTS lan_files("
    +"archive_key TEXT NOT NULL,path TEXT NOT NULL,last_hash TEXT NOT NULL,"
    +"sig TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(archive_key,path))");
  }
 }
 private static ReceiptsDb dbHelper;
 private static synchronized android.database.sqlite.SQLiteDatabase db(Context c){
  if(dbHelper==null)dbHelper=new ReceiptsDb(c);
  android.database.sqlite.SQLiteDatabase database=dbHelper.getWritableDatabase();
  migrateLegacyReceipts(c,database);
  migrateLegacyPairsAndHistory(c,database);
  return database;
 }
 private static void migrateLegacyReceipts(Context c,android.database.sqlite.SQLiteDatabase database){
  SharedPreferences syncPrefs=prefs(c);
  if(syncPrefs.getBoolean("receiptsMigrated",false))return;
  SharedPreferences legacy=c.getSharedPreferences("file-receipts",0);
  Map<String,?> all=legacy.getAll();
  if(!all.isEmpty()){
   database.beginTransaction();
   try{
    for(Object raw:all.values()){
     if(!(raw instanceof String))continue;
     try{
      JSONObject v=new JSONObject((String)raw);
      String pair=v.optString("pair"),path=v.optString("path");
      if(pair.isEmpty()||path.isEmpty())continue;
      android.content.ContentValues row=new android.content.ContentValues();
      row.put("pair",pair);row.put("path",path);row.put("name",v.optString("name"));
      row.put("remote_id",v.optString("id"));row.put("tag",v.optString("tag"));
      row.put("hash",v.optString("hash"));row.put("size",v.optLong("size",0));
      row.put("phone_modified",v.optLong("phoneModified",0));
      database.insertWithOnConflict("receipts",null,row,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
     }catch(Exception ignored){}
    }
    database.setTransactionSuccessful();
   }finally{database.endTransaction();}
   legacy.edit().clear().commit();
  }
  syncPrefs.edit().putBoolean("receiptsMigrated",true).apply();
 }
 private static void migrateLegacyPairsAndHistory(Context c,android.database.sqlite.SQLiteDatabase database){
  SharedPreferences syncPrefs=prefs(c);
  if(syncPrefs.getBoolean("pairsHistoryMigrated",false))return;
  database.beginTransaction();
  try{
   JSONArray oldPairs=new JSONArray(syncPrefs.getString("pairs","[]"));
   for(int i=0;i<oldPairs.length();i++){
    JSONObject p=oldPairs.getJSONObject(i);
    String id=p.optString("id");
    if(id.isEmpty())continue;
    android.content.ContentValues row=new android.content.ContentValues();
    row.put("id",id);row.put("provider",SyncProvider.from(p.optString("provider")).id);
    row.put("json",p.toString());row.put("sort_order",i);
    database.insertWithOnConflict("pairs",null,row,android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
   }
   JSONArray oldHistory=new JSONArray(syncPrefs.getString("history","[]"));
   for(int i=oldHistory.length()-1;i>=0;i--){
    JSONObject h=oldHistory.getJSONObject(i);
    android.content.ContentValues row=new android.content.ContentValues();
    row.put("time",h.optLong("time",System.currentTimeMillis()));
    String pairId=h.optString("pairId","");
    if(!pairId.isEmpty())row.put("pair_id",pairId);
    row.put("message",h.optString("message"));
    database.insert("history",null,row);
   }
   database.setTransactionSuccessful();
  }catch(Exception ignored){}
  finally{database.endTransaction();}
  syncPrefs.edit().putBoolean("pairsHistoryMigrated",true).apply();
 }
 private static JSONObject rowToJson(android.database.Cursor cursor)throws JSONException{
  return new JSONObject()
   .put("phoneModified",cursor.getLong(6))
   .put("pair",cursor.getString(0))
   .put("path",cursor.getString(1))
   .put("name",cursor.getString(2))
   .put("id",cursor.getString(3))
   .put("tag",cursor.getString(4))
   .put("hash",cursor.getString(5))
   .put("size",cursor.getLong(7));
 }
 static synchronized JSONObject receipt(Context c,String pair,String path)throws Exception{
  try(android.database.Cursor cursor=db(c).rawQuery(
   "SELECT pair,path,name,remote_id,tag,hash,phone_modified,size FROM receipts WHERE pair=? AND path=?",
   new String[]{pair,path})){
   return cursor.moveToFirst()?rowToJson(cursor):null;
  }
 }
 static synchronized void receipt(Context c,String pair,String path,String name,VersionedBackup.Receipt receipt)throws Exception{receipt(c,pair,path,name,receipt,0);}
 static synchronized void receipt(Context c,String pair,String path,String name,VersionedBackup.Receipt receipt,long phoneModified)throws Exception{
  android.content.ContentValues row=new android.content.ContentValues();
  row.put("pair",pair);row.put("path",path);row.put("name",name);
  row.put("remote_id",receipt.id);row.put("tag",receipt.tag==null?"":receipt.tag);
  row.put("hash",receipt.hash);row.put("size",receipt.size);row.put("phone_modified",phoneModified);
  if(db(c).insertWithOnConflict("receipts",null,row,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)<0)
   throw new java.io.IOException("Could not save backup receipt");
 }
 static synchronized void forget(Context c,String pair,String path)throws Exception{
  db(c).delete("receipts","pair=? AND path=?",new String[]{pair,path});
 }
 /** Clears a folder pair's sync records and upload checkpoints. Used when its folders change, so nothing is mistaken for a deletion. */
 static synchronized void forgetAll(Context c,String pair)throws Exception{
  db(c).delete("receipts","pair=?",new String[]{pair});
  clearCheckpoints(c.getSharedPreferences("upload-checkpoints",Context.MODE_PRIVATE),pair);
  clearCheckpoints(c.getSharedPreferences("gdrive-upload-checkpoints",Context.MODE_PRIVATE),pair);
 }
 private static void clearCheckpoints(SharedPreferences checkpoints,String pair){
  SharedPreferences.Editor edit=checkpoints.edit();
  String prefix=pair+":";
  for(String key:checkpoints.getAll().keySet())if(key.equals(pair)||key.startsWith(prefix))edit.remove(key);
  edit.apply();
 }
 /** Sync records for every file inside a folder (path prefix ending in "/"). */
 static synchronized Map<String,JSONObject> under(Context c,String pair,String prefix)throws Exception{
  Map<String,JSONObject> found=new HashMap<>();
  try(android.database.Cursor cursor=db(c).rawQuery(
   "SELECT pair,path,name,remote_id,tag,hash,phone_modified,size FROM receipts WHERE pair=? AND path>=? AND substr(path,1,?)=?",
   new String[]{pair,prefix,String.valueOf(prefix.length()),prefix})){
   while(cursor.moveToNext()){
    JSONObject value=rowToJson(cursor);
    found.put(value.getString("path"),value);
   }
  }
  return found;
 }
 static synchronized JSONArray files(Context c,String pair)throws Exception{
  JSONArray files=new JSONArray();
  try(android.database.Cursor cursor=db(c).rawQuery(
   "SELECT pair,path,name,remote_id,tag,hash,phone_modified,size FROM receipts WHERE pair=? ORDER BY path",
   new String[]{pair})){
   while(cursor.moveToNext())files.put(rowToJson(cursor));
  }
  return files;
 }
 static synchronized JSONArray historyList(Context c,String pairId,int limit)throws Exception{
  JSONArray result=new JSONArray();
  String sql=pairId==null
   ?"SELECT time,pair_id,message FROM history ORDER BY id DESC LIMIT ?"
   :"SELECT time,pair_id,message FROM history WHERE pair_id=? ORDER BY id DESC LIMIT ?";
  String[] args=pairId==null?new String[]{String.valueOf(limit)}:new String[]{pairId,String.valueOf(limit)};
  try(android.database.Cursor cursor=db(c).rawQuery(sql,args)){
   while(cursor.moveToNext()){
    result.put(new JSONObject().put("time",cursor.getLong(0))
     .put("pairId",cursor.isNull(1)?"":cursor.getString(1)).put("message",cursor.getString(2)));
   }
  }
  return result;
 }
 static synchronized void history(Context c,String pairId,String message){
  try{
   long now=System.currentTimeMillis();
   android.database.sqlite.SQLiteDatabase database=db(c);
   android.content.ContentValues row=new android.content.ContentValues();
   row.put("time",now);
   if(pairId!=null)row.put("pair_id",pairId);
   row.put("message",message);
   database.insert("history",null,row);
   // Keep newest 500 history entries globally so the table stays bounded.
   database.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 500)");
   prefs(c).edit().putLong("historyVersion",now).apply();
  }catch(Exception ignored){/* History must never contain credentials or response bodies. */}
 }
 static final class LanFileState {
  final String lastHash,sig,hash;
  LanFileState(String lastHash,String sig,String hash){
   this.lastHash=lastHash==null?"":lastHash;
   this.sig=sig==null?"":sig;
   this.hash=hash==null?"":hash;
  }
 }
 static synchronized Map<String,LanFileState> lanState(Context c,String archiveKey){
  Map<String,LanFileState> result=new HashMap<>();
  android.database.sqlite.SQLiteDatabase database=db(c);
  try(android.database.Cursor cursor=database.rawQuery(
   "SELECT path,last_hash,sig,hash FROM lan_files WHERE archive_key=?",new String[]{archiveKey})){
   while(cursor.moveToNext()){
    result.put(cursor.getString(0),new LanFileState(cursor.getString(1),cursor.getString(2),cursor.getString(3)));
   }
  }
  if(result.isEmpty()){
   SharedPreferences legacy=c.getSharedPreferences("lan-sync-state",Context.MODE_PRIVATE);
   String rawState=legacy.getString("state-"+archiveKey,null);
   String rawHashes=legacy.getString("hashes-"+archiveKey,null);
   if(rawState!=null||rawHashes!=null){
    try{
     JSONObject stateJson=rawState==null?new JSONObject():new JSONObject(rawState);
     JSONObject hashJson=rawHashes==null?new JSONObject():new JSONObject(rawHashes);
     Set<String> allPaths=new HashSet<>();
     for(Iterator<String> it=stateJson.keys();it.hasNext();)allPaths.add(it.next());
     for(Iterator<String> it=hashJson.keys();it.hasNext();)allPaths.add(it.next());
     for(String p:allPaths){
      JSONObject h=hashJson.optJSONObject(p);
      result.put(p,new LanFileState(stateJson.optString(p,""),h==null?"":h.optString("s",""),h==null?"":h.optString("h","")));
     }
     saveLanState(c,archiveKey,result);
     legacy.edit().remove("state-"+archiveKey).remove("hashes-"+archiveKey).apply();
    }catch(Exception ignored){}
   }
  }
  return result;
 }
 static synchronized void saveLanState(Context c,String archiveKey,Map<String,LanFileState> entries){
  android.database.sqlite.SQLiteDatabase database=db(c);
  database.beginTransaction();
  try{
   database.delete("lan_files","archive_key=?",new String[]{archiveKey});
   for(Map.Entry<String,LanFileState> e:entries.entrySet()){
    LanFileState v=e.getValue();
    if(v.lastHash.isEmpty()&&v.sig.isEmpty()&&v.hash.isEmpty())continue;
    android.content.ContentValues row=new android.content.ContentValues();
    row.put("archive_key",archiveKey);
    row.put("path",e.getKey());
    row.put("last_hash",v.lastHash);
    row.put("sig",v.sig);
    row.put("hash",v.hash);
    database.insertWithOnConflict("lan_files",null,row,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
   }
   database.setTransactionSuccessful();
  }finally{database.endTransaction();}
 }
}
