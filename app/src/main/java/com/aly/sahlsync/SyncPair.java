package com.aly.sahlsync;

import org.json.JSONException;
import org.json.JSONObject;

final class SyncPair {
 final String id;
 String name="",local="",remote="",remoteName="",account="",accountLabel="",exclude="",scheduleGeneration="";
 SyncProvider provider=SyncProvider.ONEDRIVE;
 SyncDirection direction=SyncDirection.UPLOAD;
 boolean unmetered=true,charging,daily,syncDeletions=true,notify=true;
 int keepVersions=10,scheduleMode,scheduleHour,scheduleMinute,scheduleDays=31;
 long scheduleDate,scheduleInterval;

 SyncPair(String id){this.id=id;}

 static SyncPair fromJson(JSONObject json)throws JSONException{
  SyncPair pair=new SyncPair(json.getString("id"));
  pair.name=json.optString("name");
  pair.provider=SyncProvider.from(json.optString("provider"));
  pair.local=json.optString("local");
  pair.remote=json.optString("remote");
  pair.remoteName=json.optString("remoteName");
  pair.account=json.optString("account");
  pair.accountLabel=json.optString("accountLabel");
  pair.exclude=json.optString("exclude");
  pair.direction=SyncDirection.from(json.optString("direction"));
  pair.unmetered=json.optBoolean("unmetered",true);
  pair.charging=json.optBoolean("charging",false);
  pair.daily=json.optBoolean("daily",false);
  pair.syncDeletions=json.optBoolean("syncDeletions",true);
  pair.keepVersions=json.optInt("keepVersions",10);
  pair.notify=json.optBoolean("notify",true);
  pair.scheduleMode=json.optInt("scheduleMode",0);
  pair.scheduleDate=json.optLong("scheduleDate",0);
  pair.scheduleHour=json.optInt("scheduleHour",0);
  pair.scheduleMinute=json.optInt("scheduleMinute",0);
  pair.scheduleDays=json.optInt("scheduleDays",31);
  pair.scheduleInterval=json.optLong("scheduleInterval",0);
  pair.scheduleGeneration=json.optString("scheduleGeneration");
  return pair;
 }

 JSONObject toJson()throws JSONException{
  return new JSONObject()
   .put("id",id)
   .put("name",name)
   .put("provider",provider.id)
   .put("local",local)
   .put("remote",remote)
   .put("remoteName",remoteName)
   .put("account",account)
   .put("accountLabel",accountLabel)
   .put("exclude",exclude)
   .put("direction",directionKey(direction))
   .put("unmetered",unmetered)
   .put("charging",charging)
   .put("daily",daily)
   .put("syncDeletions",syncDeletions)
   .put("keepVersions",keepVersions)
   .put("notify",notify)
   .put("scheduleMode",scheduleMode)
   .put("scheduleDate",scheduleDate)
   .put("scheduleHour",scheduleHour)
   .put("scheduleMinute",scheduleMinute)
   .put("scheduleDays",scheduleDays)
   .put("scheduleInterval",scheduleInterval)
   .put("scheduleGeneration",scheduleGeneration);
 }

 private static String directionKey(SyncDirection direction){
  if(direction==SyncDirection.DOWNLOAD)return "download";
  if(direction==SyncDirection.NEWEST)return "newest";
  return "upload";
 }
}
