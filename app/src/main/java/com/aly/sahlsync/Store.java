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
 static String receiptKey(String pair,String path){return pair+":"+path;}
 static synchronized JSONObject receipt(Context c,String pair,String path)throws Exception{
  String value=c.getSharedPreferences("file-receipts",0).getString(receiptKey(pair,path),null);return value==null?null:new JSONObject(value);
 }
 static synchronized void receipt(Context c,String pair,String path,String name,VersionedBackup.Receipt receipt)throws Exception{receipt(c,pair,path,name,receipt,0);}
 static synchronized void receipt(Context c,String pair,String path,String name,VersionedBackup.Receipt receipt,long phoneModified)throws Exception{
  JSONObject value=new JSONObject().put("phoneModified",phoneModified).put("pair",pair).put("path",path).put("name",name).put("id",receipt.id).put("tag",receipt.tag).put("hash",receipt.hash).put("size",receipt.size);
  if(!c.getSharedPreferences("file-receipts",0).edit().putString(receiptKey(pair,path),value.toString()).commit())throw new java.io.IOException("Could not save backup receipt");
 }
 static synchronized void forget(Context c,String pair,String path)throws Exception{
  if(!c.getSharedPreferences("file-receipts",0).edit().remove(receiptKey(pair,path)).commit())throw new java.io.IOException("Could not update sync records");
 }
 /** Clears a folder pair's sync records. Used when its folders change, so nothing is mistaken for a deletion. */
 static synchronized void forgetAll(Context c,String pair)throws Exception{
  SharedPreferences receipts=c.getSharedPreferences("file-receipts",0);
  SharedPreferences.Editor edit=receipts.edit();
  for(String key:receipts.getAll().keySet())if(key.startsWith(pair+":"))edit.remove(key);
  if(!edit.commit())throw new java.io.IOException("Could not reset sync records");
 }
 /** Sync records for every file inside a folder (path prefix ending in "/"). */
 static synchronized Map<String,JSONObject> under(Context c,String pair,String prefix)throws Exception{
  Map<String,JSONObject> found=new HashMap<>();
  String key=pair+":"+prefix;
  for(Map.Entry<String,?> entry:c.getSharedPreferences("file-receipts",0).getAll().entrySet())
   if(entry.getKey().startsWith(key)&&entry.getValue() instanceof String)
    found.put(entry.getKey().substring(pair.length()+1),new JSONObject((String)entry.getValue()));
  return found;
 }
 static synchronized JSONArray files(Context c,String pair)throws Exception{
  JSONArray files=new JSONArray();for(Object raw:c.getSharedPreferences("file-receipts",0).getAll().values()){
   if(raw instanceof String){JSONObject value=new JSONObject((String)raw);if(pair.equals(value.optString("pair")))files.put(value);}
  }return files;
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
