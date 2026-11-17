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
 static synchronized void history(Context c,String message){
  try{
   JSONArray old=new JSONArray(prefs(c).getString("history","[]")),fresh=new JSONArray();
   fresh.put(new JSONObject().put("time",System.currentTimeMillis()).put("message",message));
   for(int i=0;i<Math.min(old.length(),49);i++)fresh.put(old.get(i));
   prefs(c).edit().putString("history",fresh.toString()).commit();
  }catch(Exception ignored){/* History must never contain credentials or response bodies. */}
 }
}
