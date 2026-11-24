package com.aly.sahlsync;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Provider-independent archive-before-update adapter using CloudDrive. */
final class CloudBackup implements VersionedBackup.Backend {
 static final String HISTORY=".sahl-sync-history";
 static final int KEEP_VERSIONS=10;

 private final Context context;
 private final CloudDrive drive;
 private final SyncPair pair;
 private final String parent,path,name;

 CloudBackup(Context context,CloudDrive drive,SyncPair pair,String parent,String path,String name){
  this.context=context;
  this.drive=drive;
  this.pair=pair;
  this.parent=parent;
  this.path=path;
  this.name=name;
 }

 private VersionedBackup.Item item(CloudDrive.RemoteItem remote)throws Exception{
  if(remote.folder)throw new IOException("A folder blocks the backup filename");
  return remote.toBackupItem();
 }

 boolean hasCheckpoint(String filename,String hash){return drive.hasUploadCheckpoint(parent,filename,hash);}

 @Override public VersionedBackup.Item resumeCreate(String filename,File source,String hash)throws Exception{
  CloudDrive.RemoteItem resumed=drive.resumeUpload(parent,filename,source,hash);
  return resumed==null?null:item(resumed);
 }

 @Override public VersionedBackup.Item find(String filename)throws Exception{
  CloudDrive.RemoteItem found=drive.item(parent,filename);
  return found==null?null:item(found);
 }

 @Override public void reportConflict(VersionedBackup.Item remote,long localSize){
  Store.history(context,pair.id,path+": "+drive.providerName()+" returned an existing item in "+pair.remoteName
   +" (cloud "+remote.size+" bytes; phone "+localSize+" bytes).");
 }

 @Override public String hash(VersionedBackup.Item item)throws Exception{return drive.contentHash(item.id);}

 @Override public VersionedBackup.Item create(String filename,File source)throws Exception{
  return item(drive.upload(parent,filename,source,null));
 }

 VersionedBackup.Item create(String filename,File source,String sha256)throws Exception{
  return item(drive.upload(parent,filename,source,sha256));
 }

 @Override public VersionedBackup.Item update(VersionedBackup.Item expected,File source)throws Exception{
  CloudDrive.RemoteItem target=new CloudDrive.RemoteItem(expected.id,name,false,expected.size,0,expected.tag,"",parent,true,null);
  return item(drive.update(target,source));
 }

 static String bucket(String path)throws Exception{
  return BackupRules.hash(new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)),null);
 }

 private String archiveFolder()throws Exception{
  String root=drive.ensureFolder(pair.remote,HISTORY);
  String pairFolder=drive.ensureFolder(root,pair.id);
  return drive.ensureFolder(pairFolder,bucket(path));
 }

 @Override public void archive(VersionedBackup.Item current,String expectedHash)throws Exception{
  File snapshot=File.createTempFile("snapshot-",".tmp",context.getCacheDir());
  try{
   String downloaded=drive.download(current.id,snapshot);
   if(!expectedHash.equals(downloaded)||snapshot.length()!=current.size)
    throw new IOException("Cloud file changed before archiving");
   archiveLocal(snapshot,expectedHash);
  }finally{snapshot.delete();}
 }

 /** Stores a verified copy of a local file in Version history, then keeps only the newest copies. */
 void archiveLocal(File snapshot,String expectedHash)throws Exception{
  File info=File.createTempFile("snapshot-info-",".json",context.getCacheDir());
  try{
   String folder=archiveFolder();
   CloudDrive.RemoteItem existing=drive.item(folder,"origin.json");
   if(existing!=null){
    drive.download(existing.id,info);
    if(info.length()>65536)throw new IOException("Invalid history metadata");
    JSONObject origin=new JSONObject(new String(java.nio.file.Files.readAllBytes(info.toPath()),StandardCharsets.UTF_8));
    if(!path.equals(origin.optString("path"))||!name.equals(origin.optString("name")))
     throw new IOException("History location does not match this file");
   }else{
    JSONObject origin=new JSONObject().put("format",1).put("path",path).put("name",name).put("pair",pair.id);
    try(OutputStream out=new FileOutputStream(info)){out.write(origin.toString().getBytes(StandardCharsets.UTF_8));}
    drive.upload(folder,"origin.json",info,null);
   }
   String filename=System.currentTimeMillis()+"-"+UUID.randomUUID()+"-"+expectedHash+".snapshot";
   CloudDrive.RemoteItem archived=drive.upload(folder,filename,snapshot,expectedHash);
   if(archived.size!=snapshot.length()||!expectedHash.equals(drive.contentHash(archived.id)))
    throw new IOException("Archived copy could not be verified");
   prune();
  }finally{info.delete();}
 }

 private void prune(){
  try{
   List<CloudDrive.RemoteItem> all=versions(drive,pair,path);
   int keep=pair.keepVersions;
   if(keep<=0)return;
   for(int i=keep;i<all.size();i++)drive.delete(all.get(i).id,null);
  }catch(Exception ignored){/* Cleanup is best-effort and never blocks a sync. */}
 }

 private static long snapshotTime(CloudDrive.RemoteItem item){
  return Long.parseLong(item.name.substring(0,item.name.indexOf('-')));
 }

 static List<CloudDrive.RemoteItem> versions(CloudDrive drive,SyncPair pair,String path)throws Exception{
  CloudDrive.RemoteItem root=drive.item(pair.remote,HISTORY);
  if(root==null)return Collections.emptyList();
  CloudDrive.RemoteItem pairFolder=drive.item(root.id,pair.id);
  if(pairFolder==null)return Collections.emptyList();
  CloudDrive.RemoteItem folder=drive.item(pairFolder.id,bucket(path));
  if(folder==null)return Collections.emptyList();
  List<CloudDrive.RemoteItem> sorted=new ArrayList<>();
  for(CloudDrive.RemoteItem child:drive.children(folder.id)){
   if(!child.folder&&VersionedBackup.snapshotHash(child.name)!=null)sorted.add(child);
  }
  sorted.sort((a,b)->Long.compare(snapshotTime(b),snapshotTime(a)));
  return sorted;
 }
}
