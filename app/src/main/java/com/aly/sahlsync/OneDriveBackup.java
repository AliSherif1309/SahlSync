package com.aly.sahlsync;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** OneDrive adapter. History is ordinary Sahl-owned snapshot files, not native versions. */
final class OneDriveBackup implements VersionedBackup.Backend {
 static final String HISTORY=".sahl-sync-history";
 private final Context context;private final Graph graph;private final JSONObject pair;private final String parent,path,name;
 OneDriveBackup(Context context,Graph graph,JSONObject pair,String parent,String path,String name){this.context=context;this.graph=graph;this.pair=pair;this.parent=parent;this.path=path;this.name=name;}
 private VersionedBackup.Item item(JSONObject json)throws Exception{
  if(!json.has("file"))throw new IOException("A folder blocks the backup filename");
  return new VersionedBackup.Item(json.getString("id"),json.optString("eTag"),json.getLong("size"));
 }
 public VersionedBackup.Item resumeCreate(String filename,File source,String hash)throws Exception{
  JSONObject resumed=graph.resumeUpload(parent,filename,source,hash);
  return resumed==null?null:item(resumed);
 }
 public VersionedBackup.Item find(String filename)throws Exception{
  try{return item(graph.item(parent,filename));}catch(Graph.HttpError e){if(e.status==404)return null;throw e;}
 }
 public void reportConflict(VersionedBackup.Item remote,long localSize){
  Store.history(context,pair.optString("id"),path+": OneDrive returned an existing item in "+pair.optString("remoteName")
   +" (cloud "+remote.size+" bytes; phone "+localSize+" bytes).");
 }
 public String hash(VersionedBackup.Item item)throws Exception{return graph.contentHash(item.id);}
 public VersionedBackup.Item create(String filename,File source)throws Exception{return item(graph.upload(parent,filename,source));}
 public VersionedBackup.Item update(VersionedBackup.Item expected,File source)throws Exception{return item(graph.updateFile(expected.id,expected.tag,source));}
 static String bucket(String path)throws Exception{return BackupRules.hash(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)),null);}
 private String archiveFolder()throws Exception{
  String root=graph.folder(pair.getString("remote"),HISTORY);
  String pairFolder=graph.folder(root,pair.getString("id"));
  return graph.folder(pairFolder,bucket(path));
 }
 public void archive(VersionedBackup.Item current,String expectedHash)throws Exception{
  File snapshot=File.createTempFile("snapshot-",".tmp",context.getCacheDir());
  File info=File.createTempFile("snapshot-info-",".json",context.getCacheDir());
  try{
   String downloaded=graph.download(current.id,snapshot);
   if(!expectedHash.equals(downloaded)||snapshot.length()!=current.size)throw new IOException("Cloud file changed before archiving");
   String folder=archiveFolder();
   try{
    JSONObject existing=graph.item(folder,"origin.json");graph.download(existing.getString("id"),info);
    if(info.length()>65536)throw new IOException("Invalid history metadata");
    JSONObject origin=new JSONObject(new String(java.nio.file.Files.readAllBytes(info.toPath()),StandardCharsets.UTF_8));
    if(!path.equals(origin.optString("path"))||!name.equals(origin.optString("name")))throw new IOException("History location does not match this file");
   }catch(Graph.HttpError e){
    if(e.status!=404)throw e;
    JSONObject origin=new JSONObject().put("format",1).put("path",path).put("name",name).put("pair",pair.getString("id"));
    try(OutputStream out=new FileOutputStream(info)){out.write(origin.toString().getBytes(StandardCharsets.UTF_8));}
    graph.upload(folder,"origin.json",info);
   }
   String filename=System.currentTimeMillis()+"-"+UUID.randomUUID()+"-"+expectedHash+".snapshot";
   JSONObject archived=graph.upload(folder,filename,snapshot);
   if(archived.optLong("size",-1)!=snapshot.length()||!expectedHash.equals(graph.contentHash(archived.getString("id"))))throw new IOException("Archived copy could not be verified");
  }finally{snapshot.delete();info.delete();}
 }
 private static long snapshotTime(JSONObject item){String name=item.optString("name");return Long.parseLong(name.substring(0,name.indexOf('-')));}
 static JSONArray versions(Graph graph,JSONObject pair,String path)throws Exception{
  try{
   String root=graph.item(pair.getString("remote"),HISTORY).getString("id");
   String pairFolder=graph.item(root,pair.getString("id")).getString("id");
   String folder=graph.item(pairFolder,bucket(path)).getString("id");
   JSONArray children=graph.children(folder),result=new JSONArray();
   java.util.List<JSONObject> sorted=new java.util.ArrayList<>();
   for(int i=children.length()-1;i>=0;i--){JSONObject child=children.getJSONObject(i);if(child.has("file")&&VersionedBackup.snapshotHash(child.optString("name"))!=null)sorted.add(child);}
   sorted.sort((a,b)->Long.compare(snapshotTime(b),snapshotTime(a)));for(JSONObject entry:sorted)result.put(entry);
   return result;
  }catch(Graph.HttpError e){if(e.status==404)return new JSONArray();throw e;}
 }
}
