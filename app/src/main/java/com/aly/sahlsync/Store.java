package com.aly.sahlsync;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

final class Store {
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("sync",Context.MODE_PRIVATE);}
 static synchronized JSONArray pairs(Context c)throws Exception{return new JSONArray(prefs(c).getString("pairs","[]"));}
 static synchronized void add(Context c,JSONObject pair)throws Exception{
  JSONArray all=pairs(c);all.put(pair);if(!prefs(c).edit().putString("pairs",all.toString()).commit())throw new java.io.IOException("Could not save folder pair");
 }
 static JSONObject pair(Context c,String id)throws Exception{
  JSONArray all=pairs(c);for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))return all.getJSONObject(i);
  throw new java.io.IOException("Folder pair not found");
 }
 static String receiptKey(String pair,String path){return pair+":"+path;}
 static synchronized JSONObject receipt(Context c,String pair,String path)throws Exception{
  String value=c.getSharedPreferences("file-receipts",0).getString(receiptKey(pair,path),null);return value==null?null:new JSONObject(value);
 }
 static synchronized void receipt(Context c,String pair,String path,String name,VersionedBackup.Receipt receipt)throws Exception{
  JSONObject value=new JSONObject().put("pair",pair).put("path",path).put("name",name).put("id",receipt.id).put("tag",receipt.tag).put("hash",receipt.hash).put("size",receipt.size);
  if(!c.getSharedPreferences("file-receipts",0).edit().putString(receiptKey(pair,path),value.toString()).commit())throw new java.io.IOException("Could not save backup receipt");
 }
 static synchronized JSONArray files(Context c,String pair)throws Exception{
  JSONArray files=new JSONArray();for(Object raw:c.getSharedPreferences("file-receipts",0).getAll().values()){
   if(raw instanceof String){JSONObject value=new JSONObject((String)raw);if(pair.equals(value.optString("pair")))files.put(value);}
  }return files;
 }
 static synchronized void history(Context c,String message){
  try{
   JSONArray old=new JSONArray(prefs(c).getString("history","[]")),fresh=new JSONArray();
   fresh.put(new JSONObject().put("time",System.currentTimeMillis()).put("message",message));
   for(int i=0;i<Math.min(old.length(),49);i++)fresh.put(old.get(i));
   prefs(c).edit().putString("history",fresh.toString()).commit();
  }catch(Exception ignored){/* History must never contain credentials or response bodies. */}
 }
}
