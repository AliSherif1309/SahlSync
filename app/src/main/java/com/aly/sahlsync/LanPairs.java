package com.aly.sahlsync;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LanPairs {
 private final SharedPreferences settings;
 LanPairs(SharedPreferences settings){this.settings=settings;}

 static final class Pair {
  final String id;
  String name,code="",tree="",mode="",result="",archiveKey="";
  boolean legacyArchive;
  long lastCompleted;
  int autoMinutes,autoTime=-1;
  boolean deletions=true,autoCharging;
  Pair(String id,String name){this.id=id;this.name=name;}
  boolean ready(){return !code.isEmpty()&&!tree.isEmpty();}
 }

 List<Pair> list()throws JSONException{
  List<Pair> pairs=new ArrayList<>();
  String stored=settings.getString("folderPairs",null);
  if(stored==null){
   Pair first=new Pair(UUID.randomUUID().toString(),"Folder pair 1");
   first.code=settings.getString("code","");
   first.tree=settings.getString("tree","");
   first.mode=settings.getString("mode","");
   first.result=settings.getString("result","");
   first.legacyArchive=!first.code.isEmpty();
   pairs.add(first);
   save(pairs);
   return pairs;
  }
  JSONArray items=new JSONArray(stored);
  for(int i=0;i<items.length();i++){
   JSONObject value=items.getJSONObject(i);
   Pair pair=new Pair(value.getString("id"),value.getString("name"));
   pair.code=value.optString("code");pair.tree=value.optString("tree");pair.mode=value.optString("mode");
   pair.result=value.optString("result");pair.archiveKey=value.optString("archiveKey");
   pair.legacyArchive=value.optBoolean("legacyArchive");pair.lastCompleted=value.optLong("lastCompleted");
   pair.autoMinutes=value.optInt("autoMinutes");pair.autoTime=value.optInt("autoTime",-1);pair.deletions=value.optBoolean("deletions",true);pair.autoCharging=value.optBoolean("autoCharging");
   pairs.add(pair);
  }
  return pairs;
 }

 Pair add(String name)throws JSONException{
  Pair pair=new Pair(UUID.randomUUID().toString(),name);
  List<Pair> pairs=list();
  pairs.add(pair);
  save(pairs);
  return pair;
 }

 void update(Pair pair)throws JSONException{
  List<Pair> pairs=list();
  for(int i=0;i<pairs.size();i++){
   if(pairs.get(i).id.equals(pair.id)){pairs.set(i,pair);save(pairs);return;}
  }
 }

 Pair get(String id)throws JSONException{
  for(Pair pair:list())if(pair.id.equals(id))return pair;
  return null;
 }

 String activeId(){return settings.getString("activeFolderPair","");}
 void remove(String id)throws JSONException{
  List<Pair> pairs=list();
  pairs.removeIf(p->p.id.equals(id));
  save(pairs);
  if(id.equals(activeId()))settings.edit().remove("activeFolderPair").apply();
 }
 void select(String id){settings.edit().putString("activeFolderPair",id).apply();}

 private void save(List<Pair> pairs)throws JSONException{
  JSONArray items=new JSONArray();
  for(Pair p:pairs)items.put(new JSONObject().put("id",p.id).put("name",p.name)
   .put("code",p.code).put("tree",p.tree).put("mode",p.mode).put("result",p.result)
   .put("archiveKey",p.archiveKey).put("legacyArchive",p.legacyArchive).put("lastCompleted",p.lastCompleted)
   .put("autoMinutes",p.autoMinutes).put("autoTime",p.autoTime).put("deletions",p.deletions).put("autoCharging",p.autoCharging));
  settings.edit().putString("folderPairs",items.toString()).apply();
 }
}
