package com.aly.sahlsync;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** CloudDrive adapter for Microsoft OneDrive via Graph. */
final class OneDriveDrive implements CloudDrive {
 private final Graph graph;

 OneDriveDrive(Graph graph){this.graph=graph;}

 @Override public String providerName(){return "OneDrive";}

 @Override public RemoteItem item(String parentId,String name)throws Exception{
  return call(()->{
   try{return toRemoteItem(graph.item(parentId,name));}
   catch(Graph.HttpError e){if(e.status==404)return null;throw e;}
  });
 }

 @Override public List<RemoteItem> children(String parentId)throws Exception{
  return call(()->{
   JSONArray rows=graph.children(parentId);
   List<RemoteItem> result=new ArrayList<>(rows.length());
   for(int i=0;i<rows.length();i++)result.add(toRemoteItem(rows.getJSONObject(i)));
   return result;
  });
 }

 @Override public String ensureFolder(String parentId,String name)throws Exception{
  return call(()->graph.folder(parentId,name));
 }

 @Override public String contentHash(String itemId)throws Exception{
  return call(()->graph.contentHash(itemId));
 }

 @Override public String download(String itemId,File destination)throws Exception{
  return call(()->graph.download(itemId,destination));
 }

 @Override public RemoteItem upload(String parentId,String name,File source,String sha256)throws Exception{
  return call(()->toRemoteItem(graph.upload(parentId,name,source,sha256)));
 }

 @Override public RemoteItem update(RemoteItem expected,File source)throws Exception{
  return call(()->toRemoteItem(graph.updateFile(expected.id,expected.versionTag,source)));
 }

 @Override public boolean hasUploadCheckpoint(String parentId,String name,String sha256){
  return graph.hasUploadCheckpoint(parentId,name,sha256);
 }

 @Override public RemoteItem resumeUpload(String parentId,String name,File source,String sha256)throws Exception{
  return call(()->{
   JSONObject resumed=graph.resumeUpload(parentId,name,source,sha256);
   return resumed==null?null:toRemoteItem(resumed);
  });
 }

 @Override public void delete(String itemId,String versionTag)throws Exception{
  call(()->{graph.delete(itemId,versionTag);return null;});
 }

 @Override public boolean wantsQuickXor(){return true;}
 @Override public boolean wantsMd5(){return false;}

 @Override public boolean matchesFingerprint(RemoteItem item,LocalDigest local){
  if(item==null||local==null||local.quickXor==null||item.fingerprint.isEmpty())return false;
  return item.size==local.size&&item.fingerprint.equals(local.quickXor);
 }

 private static RemoteItem toRemoteItem(JSONObject json)throws Exception{
  boolean folder=json.has("folder");
  boolean supported=folder||(json.has("file")&&!json.has("remoteItem")&&!json.has("package"));
  JSONObject parentRef=json.optJSONObject("parentReference");
  String parentId=parentRef==null?"":parentRef.optString("id");
  return new RemoteItem(
   json.getString("id"),
   json.getString("name"),
   folder,
   json.optLong("size",-1),
   parseModified(json.optString("lastModifiedDateTime")),
   json.optString("eTag"),
   quickXorHash(json),
   parentId,
   supported,
   supported?null:"Unsupported OneDrive item."
  );
 }

 private static String quickXorHash(JSONObject json){
  JSONObject file=json.optJSONObject("file");
  JSONObject hashes=file==null?null:file.optJSONObject("hashes");
  return hashes==null?"":hashes.optString("quickXorHash");
 }

 private static long parseModified(String timestamp){
  if(timestamp==null||timestamp.isEmpty())return 0;
  try{return Instant.parse(timestamp).toEpochMilli();}
  catch(java.time.DateTimeException e){return 0;}
 }

 private interface Call<T> { T run()throws Exception; }

 private static <T> T call(Call<T> action)throws Exception{
  try{return action.run();}
  catch(Auth.SignInRequired e){throw new AuthRequired(e.getMessage(),e);}
  catch(UploadSession.ConnectionFailure e){throw new ConnectionInterrupted(e.getMessage(),e);}
  catch(Graph.HttpError e){throw new HttpError("OneDrive",e.status);}
 }
}
