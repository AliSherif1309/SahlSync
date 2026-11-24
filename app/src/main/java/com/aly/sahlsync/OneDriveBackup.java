package com.aly.sahlsync;

import java.util.List;
import org.json.*;

/** Compatibility facade for OneDrive version history queries from MainActivity. */
final class OneDriveBackup {
 static final String HISTORY=CloudBackup.HISTORY;

 static JSONArray versions(Graph graph,JSONObject pair,String path)throws Exception{
  List<CloudDrive.RemoteItem> list=CloudBackup.versions(new OneDriveDrive(graph),SyncPair.fromJson(pair),path);
  JSONArray result=new JSONArray();
  for(CloudDrive.RemoteItem item:list){
   result.put(new JSONObject().put("id",item.id).put("name",item.name).put("size",item.size));
  }
  return result;
 }
}
