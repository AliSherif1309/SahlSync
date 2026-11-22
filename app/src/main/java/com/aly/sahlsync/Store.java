package com.aly.sahlsync;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class Store {
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("sync",Context.MODE_PRIVATE);}
 static synchronized JSONArray pairs(Context c)throws Exception{return new JSONArray(prefs(c).getString("pairs","[]"));}
 static synchronized void add(Context c,JSONObject pair)throws Exception{
  JSONArray all=pairs(c);all.put(pair);if(!prefs(c).edit().putString("pairs",all.toString()).commit())throw new java.io.IOException("Could not save folder pair");
 }
 static synchronized void update(Context c,JSONObject pair)throws Exception{
  JSONArray all=pairs(c);boolean found=false;
  for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(pair.getString("id"))){all.put(i,pair);found=true;break;}
  if(!found)throw new java.io.IOException("Folder pair not found");
  if(!prefs(c).edit().putString("pairs",all.toString()).commit())throw new java.io.IOException("Could not save settings");
 }
 static synchronized void remove(Context c,String id)throws Exception{
  JSONArray all=pairs(c),kept=new JSONArray();
  for(int i=0;i<all.length();i++)if(!all.getJSONObject(i).getString("id").equals(id))kept.put(all.get(i));
  if(!prefs(c).edit().putString("pairs",kept.toString()).remove("history-"+id).commit())throw new java.io.IOException("Could not remove folder");
  forgetAll(c,id);
 }
 static JSONObject pair(Context c,String id)throws Exception{
  JSONArray all=pairs(c);for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))return all.getJSONObject(i);
  throw new java.io.IOException("Folder pair not found");
 }
 private static final class ReceiptsDb extends android.database.sqlite.SQLiteOpenHelper {
  ReceiptsDb(Context c){super(c.getApplicationContext(),"sahl_sync.db",null,1);}
  @Override public void onConfigure(android.database.sqlite.SQLiteDatabase db){db.enableWriteAheadLogging();}
  @Override public void onCreate(android.database.sqlite.SQLiteDatabase db){
   db.execSQL("CREATE TABLE IF NOT EXISTS receipts("
    +"pair TEXT NOT NULL,path TEXT NOT NULL,name TEXT NOT NULL,"
    +"remote_id TEXT NOT NULL,tag TEXT NOT NULL,hash TEXT NOT NULL,"
    +"size INTEGER NOT NULL,phone_modified INTEGER NOT NULL,PRIMARY KEY(pair,path))");
   db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipts_pair ON receipts(pair)");
  }
  @Override public void onUpgrade(android.database.sqlite.SQLiteDatabase db,int oldVersion,int newVersion){}
 }
 private static ReceiptsDb dbHelper;
 private static synchronized android.database.sqlite.SQLiteDatabase db(Context c){
  if(dbHelper==null)dbHelper=new ReceiptsDb(c);
  android.database.sqlite.SQLiteDatabase database=dbHelper.getWritableDatabase();
  migrateLegacyReceipts(c,database);
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
  SharedPreferences checkpoints=c.getSharedPreferences("upload-checkpoints",Context.MODE_PRIVATE);
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
 static synchronized void history(Context c,String pairId,String message){
  try{
   JSONArray old=new JSONArray(prefs(c).getString("history","[]")),fresh=new JSONArray();
   JSONObject entry=new JSONObject().put("time",System.currentTimeMillis()).put("pairId",pairId).put("message",message);
   fresh.put(entry);
   for(int i=0;i<Math.min(old.length(),49);i++)fresh.put(old.get(i));
   SharedPreferences.Editor edit=prefs(c).edit().putString("history",fresh.toString());
   if(pairId!=null){
    String key="history-"+pairId;
    JSONArray previous=new JSONArray(prefs(c).getString(key,"[]"));
    JSONArray folderHistory=new JSONArray().put(entry);
    for(int i=0;i<Math.min(previous.length(),49);i++)folderHistory.put(previous.get(i));
    edit.putString(key,folderHistory.toString());
   }
   edit.commit();
  }catch(Exception ignored){/* History must never contain credentials or response bodies. */}
 }
}
