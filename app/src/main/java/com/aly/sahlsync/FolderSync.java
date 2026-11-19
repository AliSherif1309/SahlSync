package com.aly.sahlsync;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import org.json.*;

final class FolderSync {
 private static final String TEMP_PREFIX="sahl-sync-staging-";
 private static final String HISTORY_PREFIX="sahl-sync-previous-";
 private final Context context;
 private final ContentResolver resolver;
 private final Graph graph;
 private final JSONObject pair;
 private final String pairId;
 private final SyncDirection direction;
 private final BiConsumer<FolderSync,String> progress;
 int uploaded,downloaded,deleted,unchanged,excluded,conflicts,filesChecked;
 private int visited;
 private final Set<String> clashes=new HashSet<>(),clashNames=new HashSet<>();

 FolderSync(Context context,Graph graph,JSONObject pair,BiConsumer<FolderSync,String> progress){
  this.context=context;
  resolver=context.getContentResolver();
  this.graph=graph;
  this.pair=pair;
  pairId=pair.optString("id");
  direction=SyncDirection.from(pair.optString("direction"));
  this.progress=progress;
 }

 static boolean writable(ContentResolver resolver,Uri tree){
  for(UriPermission grant:resolver.getPersistedUriPermissions())
   if(grant.isWritePermission()&&grant.getUri().equals(tree))return true;
  return false;
 }

 void run(Uri tree)throws Exception{
  if(!writable(resolver,tree))throw new IOException("Allow write access to this phone folder in backup settings");
  DocumentFile root=DocumentFile.fromTreeUri(context,tree);
  if(root==null||!root.isDirectory()||!root.canWrite())throw new IOException("Phone folder is not writable");
  walk(root.getUri(),pair.getString("remote"),"",0);
 }

 private static final class Local {
  final Uri uri;
  final String name,mime;
  final long modified,size;
  Local(Uri uri,String name,String mime,long modified,long size){
   this.uri=uri;this.name=name;this.mime=mime;this.modified=modified;this.size=size;
  }
  boolean directory(){return Document.MIME_TYPE_DIR.equals(mime);}
 }

 private Map<String,Local> localChildren(Uri parent)throws IOException{
  Map<String,Local> result=new LinkedHashMap<>();
  Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
  String[] columns={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_LAST_MODIFIED,Document.COLUMN_SIZE};
  try(android.database.Cursor rows=resolver.query(children,columns,null,null,null)){
   if(rows==null)throw new IOException("Cannot list phone folder");
   while(rows.moveToNext()){
    BackupCancellation.check();
    String name=rows.getString(1);
    if(name==null)throw new IOException("Phone file name is unavailable");
    String key=key(name);
    Uri uri=DocumentsContract.buildDocumentUriUsingTree(parent,rows.getString(0));
    if(result.containsKey(key)){clashes.add(uri.toString());clashNames.add(key);continue;}
    result.put(key,new Local(uri,name,rows.getString(2),rows.getLong(3),rows.getLong(4)));
   }
  }
  return result;
 }

 private static String key(String name){return name.toLowerCase(Locale.ROOT);}
 static boolean reserved(String name){
  String lower=key(name);
  return lower.equals(OneDriveBackup.HISTORY)||lower.startsWith(TEMP_PREFIX)||lower.startsWith(HISTORY_PREFIX);
 }
 private void conflict(String path,String explanation){
  conflicts++;
  Store.history(context,pairId,path+": "+explanation);
 }

 private void walk(Uri parent,String drive,String path,int depth)throws Exception{
  if(depth>32){conflict(path,"Folder nesting is too deep; skipped.");return;}
  Map<String,Local> local=localChildren(parent);
  Map<String,JSONObject> cloud=new LinkedHashMap<>();
  JSONArray rows=graph.children(drive);
  for(int index=0;index<rows.length();index++){
   JSONObject row=rows.getJSONObject(index);
   if(cloud.containsKey(key(row.getString("name")))){clashNames.add(key(row.getString("name")));continue;}
   cloud.put(key(row.getString("name")),row);
  }
  Set<String> names=new LinkedHashSet<>(local.keySet());
  names.addAll(cloud.keySet());
  for(String key:names){
   BackupCancellation.check();
   if(++visited>100000)throw new IOException("Sync limit: 100,000 items per run");
   Local phone=local.get(key);
   JSONObject remote=cloud.get(key);
   String name=phone==null?remote.getString("name"):phone.name;
   String relative=path+name;
   if(clashNames.remove(key)){conflict(relative,"Two names differ only by capital letters; rename one. Skipped.");continue;}
   if(reserved(name)||BackupRules.excluded(relative,pair.optString("exclude"))){excluded++;continue;}
   boolean phoneFolder=phone!=null&&phone.directory();
   boolean cloudFolder=remote!=null&&remote.has("folder");
   if((phone!=null&&!phoneFolder&&(remote!=null||direction!=SyncDirection.DOWNLOAD))
    ||(remote!=null&&!cloudFolder&&remote.has("file")))filesChecked++;
   progress.accept(this,relative);
   try{BackupRules.validateName(name);}
   catch(IllegalArgumentException error){conflict(relative,"Unsupported file name.");continue;}
   if(remote!=null&&!name.equals(remote.getString("name"))){conflict(relative,"Names differ by case.");continue;}
   if((phoneFolder||cloudFolder)&&phone!=null&&remote!=null&&phoneFolder!=cloudFolder){
    conflict(relative,"A file and folder share the same name.");
    continue;
   }
   if(phoneFolder||cloudFolder){
    if(remote==null&&direction==SyncDirection.DOWNLOAD)continue;
    Uri child=phone!=null?phone.uri:makeDocument(parent,Document.MIME_TYPE_DIR,name);
    String destination=remote!=null?remote.getString("id"):graph.folder(drive,name);
    walk(child,destination,relative+"/",depth+1);
    continue;
   }
   if(remote!=null&&(!remote.has("file")||remote.has("remoteItem")||remote.has("package"))){
    conflict(relative,"Unsupported OneDrive item.");
    continue;
   }
   try{syncFile(parent,drive,name,relative,phone,remote);}
   catch(Graph.HttpError e){if(e.status!=409&&e.status!=412)throw e;conflict(relative,"OneDrive changed during sync. Retry after reviewing both copies.");}
   catch(BackupRules.FileTooLarge e){
    excluded++;
    Store.history(context,pairId,relative+": skipped (over "+BackupRules.MAX_FILE_MIB+" MiB).");
   }
   catch(LocalConflict e){conflict(relative,e.getMessage());}
  }
 }

 private void syncFile(Uri parent,String drive,String name,String path,Local phone,JSONObject cloud)throws Exception{
  if(cloud==null&&direction==SyncDirection.DOWNLOAD)return;
  JSONObject receipt=Store.receipt(context,pairId,path);
  boolean cloudUnchanged=cloud!=null&&receipt!=null&&!cloud.optString("eTag").isEmpty()
   &&cloud.optString("id").equals(receipt.optString("id"))&&cloud.optString("eTag").equals(receipt.optString("tag"))
   &&cloud.optLong("size",-1)==receipt.optLong("size",-2);
  boolean phoneUnchanged=phone!=null&&receipt!=null&&phone.modified>0
   &&phone.modified==receipt.optLong("phoneModified",-1)&&phone.size==receipt.optLong("size",-2);
  switch(pair.optBoolean("syncDeletions",true)?direction.deletion(phone!=null,cloud!=null,receipt!=null,phoneUnchanged,cloudUnchanged):SyncDirection.Deletion.NONE){
   case DELETE_CLOUD: deleteCloud(drive,name,path,cloud,receipt);return;
   case DELETE_PHONE: deletePhone(drive,name,path,phone);return;
   default: break;
  }
  if(receipt!=null&&(phone==null)!=(cloud==null)&&direction==SyncDirection.NEWEST)
   Store.history(context,pairId,path+": restored because it changed after being deleted on the other side.");
  // Fast path: neither side changed since the last sync, so skip reading and hashing the file.
  if(phoneUnchanged&&cloudUnchanged){unchanged++;return;}
  if(phone!=null)BackupRules.requireFileSize(phone.size);
  if(cloud!=null)BackupRules.requireFileSize(cloud.optLong("size",-1));
  File local=null;
  try{
   String localHash=null;
   if(phone!=null){
    local=File.createTempFile("sync-phone-",".tmp",context.getCacheDir());
    try(InputStream input=resolver.openInputStream(phone.uri);OutputStream output=new FileOutputStream(local)){
     if(input==null)throw new IOException("Cannot read phone file");
     localHash=BackupRules.hash(input,output);
    }
   }
   OneDriveBackup backend=new OneDriveBackup(context,graph,pair,drive,path,name);
   if(phone!=null&&direction==SyncDirection.NEWEST){
    VersionedBackup.Item resumed=backend.resumeCreate(name,local,localHash);
    if(resumed!=null){remember(path,name,resumed,localHash,phone.modified);uploaded++;return;}
   }
   VersionedBackup.Item remote=cloud==null?null:new VersionedBackup.Item(
    cloud.getString("id"),cloud.optString("eTag"),cloud.getLong("size"));
   String remoteHash=null;
   if(phone!=null&&remote!=null){
    remoteHash=receipt!=null&&!remote.tag.isEmpty()&&remote.id.equals(receipt.optString("id"))
     &&remote.tag.equals(receipt.optString("tag"))&&remote.size==receipt.optLong("size",-1)
     ?receipt.getString("hash"):sameQuickXor(cloud,local)?localHash:backend.hash(remote);
   }
   boolean same=localHash!=null&&localHash.equals(remoteHash)&&local.length()==remote.size;
   long cloudModified=cloud==null?0:modified(cloud.optString("lastModifiedDateTime"));
   SyncDirection.Action action=direction.choose(phone!=null,remote!=null,same,phone==null?0:phone.modified,cloudModified);
   switch(action){
    case SKIP: return;
    case CONFLICT: conflict(path,"Different copies. Check their modification times before replacing either.");return;
    case UNCHANGED: remember(path,name,remote,localHash,phone.modified);unchanged++;return;
    case UPLOAD:
     BackupCancellation.check();
     if(!stillSame(phone))throw new LocalConflict("Phone file changed during sync.");
     VersionedBackup.Item saved;
     if(remote==null)saved=backend.create(name,local);
     else{
      requireTag(remote);
      backend.archive(remote,remoteHash);
      BackupCancellation.check();
      if(!stillSame(phone))throw new LocalConflict("Phone file changed while the OneDrive copy was archived.");
      saved=backend.update(remote,local);
      if(!remote.id.equals(saved.id))throw new IOException("OneDrive file identity changed");
     }
     remember(path,name,saved,localHash,phone.modified);
     uploaded++;
     return;
    case DOWNLOAD:
     download(parent,drive,name,path,phone,localHash,remote);
     downloaded++;
   }
  }finally{if(local!=null)local.delete();}
 }

 /** Uses OneDrive's own fingerprint so matching files don't need to be downloaded to compare. */
 private static boolean sameQuickXor(JSONObject cloud,File local){
  try{
   JSONObject hashes=cloud.optJSONObject("file")==null?null:cloud.getJSONObject("file").optJSONObject("hashes");
   String expected=hashes==null?"":hashes.optString("quickXorHash");
   return !expected.isEmpty()&&cloud.optLong("size",-1)==local.length()&&expected.equals(QuickXorHash.of(local));
  }catch(Exception error){return false;}
 }

 /** Cheap check that the phone file was not edited meanwhile (size and time), instead of re-reading it. */
 private boolean stillSame(Local phone){
  String[] columns={Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED};
  try(android.database.Cursor row=resolver.query(phone.uri,columns,null,null,null)){
   return row!=null&&row.moveToFirst()&&row.getLong(0)==phone.size&&row.getLong(1)==phone.modified;
  }catch(Exception error){return false;}
 }

 private static long modified(String timestamp){
  try{return Instant.parse(timestamp).toEpochMilli();}
  catch(java.time.DateTimeException e){return 0;}
 }

 private void remember(String path,String name,VersionedBackup.Item item,String hash,long phoneModified)throws Exception{
  Store.receipt(context,pairId,path,name,new VersionedBackup.Receipt(item.id,item.tag,hash,item.size),phoneModified);
 }

 /** Deleted on the phone: keep a verified copy in OneDrive history, then remove the cloud file. */
 private void deleteCloud(String drive,String name,String path,JSONObject cloud,JSONObject receipt)throws Exception{
  BackupCancellation.check();
  VersionedBackup.Item remote=new VersionedBackup.Item(cloud.getString("id"),cloud.getString("eTag"),cloud.getLong("size"));
  new OneDriveBackup(context,graph,pair,drive,path,name).archive(remote,receipt.getString("hash"));
  graph.delete(remote.id,remote.tag);
  Store.forget(context,pairId,path);
  Store.history(context,pairId,path+": deleted from OneDrive (a copy is in Version history).");
  deleted++;
 }

 /** Deleted on OneDrive: rename the phone file to a recovery name instead of erasing it. */
 private void deletePhone(String drive,String name,String path,Local phone)throws Exception{
  BackupCancellation.check();
  File copy=File.createTempFile("sync-deleted-",".tmp",context.getCacheDir());
  try{
   String hash;
   try(InputStream input=resolver.openInputStream(phone.uri);OutputStream output=new FileOutputStream(copy)){
    if(input==null)throw new IOException("Cannot read phone file");
    hash=BackupRules.hash(input,output);
   }
   new OneDriveBackup(context,graph,pair,drive,path,name).archiveLocal(copy,hash);
  }finally{copy.delete();}
  String kept=HISTORY_PREFIX+UUID.randomUUID()+"-"+phone.name.substring(0,Math.min(80,phone.name.length()));
  if(DocumentsContract.renameDocument(resolver,phone.uri,kept)==null)throw new LocalConflict("Phone storage could not move the deleted file aside.");
  Store.forget(context,pairId,path);
  Store.history(context,pairId,path+": deleted on OneDrive; phone copy kept as "+kept+" and in Version history.");
  deleted++;
 }

 private static void requireTag(VersionedBackup.Item item)throws LocalConflict{
  if(item.tag.isEmpty())throw new LocalConflict("OneDrive did not return a file version. No file replaced.");
 }

 private void verifyCloud(String drive,String name,VersionedBackup.Item remote)throws Exception{
  JSONObject actual=graph.item(drive,name);
  if(!remote.id.equals(actual.optString("id"))||!remote.tag.equals(actual.optString("eTag"))
   ||remote.size!=actual.optLong("size",-1))throw new LocalConflict("OneDrive file changed during download.");
 }

 private void download(Uri parent,String drive,String name,String path,Local original,String originalHash,
  VersionedBackup.Item remote)throws Exception{
  requireTag(remote);
  if(remote.size<0)throw new LocalConflict("OneDrive did not report a valid file size.");
  BackupRules.requireFileSize(remote.size);
  File staged=File.createTempFile("sync-cloud-",".tmp",context.getCacheDir());
  Uri temporary=null;
  boolean published=false;
  try{
   String hash=graph.download(remote.id,staged);
   if(staged.length()!=remote.size)throw new LocalConflict("Downloaded size differs from OneDrive.");
   temporary=makeDocument(parent,"application/octet-stream",TEMP_PREFIX+UUID.randomUUID());
   try(InputStream input=new FileInputStream(staged);OutputStream output=resolver.openOutputStream(temporary,"wt")){
    if(output==null)throw new IOException("Cannot stage the download on the phone");
    BackupRules.hash(input,output);
   }
   if(!hash.equals(hash(temporary)))throw new IOException("Phone download could not be verified");
   verifyCloud(drive,name,remote);
   BackupCancellation.check();
   Local current=localChildren(parent).get(key(name));
   if(original==null){
    if(current!=null)throw new LocalConflict("A phone file appeared during download.");
   }else if(current==null||!current.uri.equals(original.uri)||!originalHash.equals(hash(current.uri))){
    throw new LocalConflict("Phone file changed during download.");
   }
   String previous=null;
   Uri preserved=null;
   if(original!=null){
    previous=HISTORY_PREFIX+UUID.randomUUID()+"-"+name.substring(0,Math.min(80,name.length()));
    preserved=DocumentsContract.renameDocument(resolver,original.uri,previous);
    if(preserved==null)throw new LocalConflict("Phone storage cannot preserve the previous copy.");
   }
   try{
    Uri result=DocumentsContract.renameDocument(resolver,temporary,name);
    if(result==null)throw new LocalConflict("Phone storage could not publish the verified download.");
    published=true;
    Local installed=localChildren(parent).get(key(name));
    if(installed==null||!name.equals(installed.name)||!result.equals(installed.uri))
     throw new LocalConflict("Phone storage used a different name for the download.");
   }catch(Exception error){
    if(preserved!=null){
     try{if(localChildren(parent).get(key(name))==null)DocumentsContract.renameDocument(resolver,preserved,name);}
     catch(Exception ignored){/* The earlier copy is still available under its recovery name. */}
    }
    throw error;
   }
   if(previous!=null)Store.history(context,pairId,path+": previous phone copy saved as "+previous+".");
   Local stored=localChildren(parent).get(key(name));
   remember(path,name,remote,hash,stored==null?0:stored.modified);
  }finally{
   if(!published&&temporary!=null){
    try{DocumentsContract.deleteDocument(resolver,temporary);}catch(Exception ignored){}
   }
   staged.delete();
  }
 }

 private String hash(Uri uri)throws Exception{
  try(InputStream input=resolver.openInputStream(uri)){
   if(input==null)throw new IOException("Cannot verify phone file");
   return BackupRules.hash(input,null);
  }
 }

 private Uri makeDocument(Uri parent,String mime,String name)throws Exception{
  Uri result=DocumentsContract.createDocument(resolver,parent,mime,name);
  if(result==null)throw new IOException("Phone storage could not create a document");
  Local created=localChildren(parent).get(key(name));
  if(created==null||!name.equals(created.name)||!result.equals(created.uri))
   throw new LocalConflict("Phone storage changed the requested file name.");
  return result;
 }

 private static final class LocalConflict extends IOException {
  LocalConflict(String message){super(message);}
 }
}
