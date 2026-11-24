package com.aly.sahlsync;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BiConsumer;
import org.json.*;

final class FolderSync {
 private static final String TEMP_PREFIX="sahl-sync-staging-";
 private static final String HISTORY_PREFIX="sahl-sync-previous-";
 private final Context context;
 private final ContentResolver resolver;
 private final CloudDrive drive;
 private final SyncPair pair;
 private final String pairId;
 private final SyncDirection direction;
 private final BiConsumer<FolderSync,String> progress;
 int uploaded,downloaded,deleted,unchanged,excluded,conflicts,filesChecked;
 private int visited;
 private final Set<String> clashes=new HashSet<>(),clashNames=new HashSet<>();

 FolderSync(Context context,CloudDrive drive,SyncPair pair,BiConsumer<FolderSync,String> progress){
  this.context=context;
  resolver=context.getContentResolver();
  this.drive=drive;
  this.pair=pair;
  pairId=pair.id;
  direction=pair.direction;
  this.progress=progress;
 }

 static boolean writable(ContentResolver resolver,Uri tree){
  for(UriPermission grant:resolver.getPersistedUriPermissions())
   if(grant.isWritePermission()&&grant.getUri().equals(tree))return true;
  return false;
 }

 void run(Uri tree)throws Exception{
  if(direction!=SyncDirection.UPLOAD&&!writable(resolver,tree))
   throw new IOException("Allow write access to this phone folder in backup settings");
  DocumentFile root=DocumentFile.fromTreeUri(context,tree);
  if(root==null||!root.isDirectory()||!root.canRead()||(direction!=SyncDirection.UPLOAD&&!root.canWrite()))
   throw new IOException(direction==SyncDirection.UPLOAD?"Phone folder is not readable":"Phone folder is not writable");
  walk(root.getUri(),pair.remote,"",0);
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
  return lower.equals(CloudBackup.HISTORY)||lower.startsWith(TEMP_PREFIX)||lower.startsWith(HISTORY_PREFIX);
 }
 private void conflict(String path,String explanation){
  conflicts++;
  Store.history(context,pairId,path+": "+explanation);
 }

 private void walk(Uri parent,String remoteFolderId,String path,int depth)throws Exception{
  if(depth>32){conflict(path,"Folder nesting is too deep; skipped.");return;}
  Map<String,Local> local=localChildren(parent);
  Map<String,CloudDrive.RemoteItem> cloud=new LinkedHashMap<>();
  for(CloudDrive.RemoteItem row:drive.children(remoteFolderId)){
   String k=key(row.name);
   if(cloud.containsKey(k)){clashNames.add(k);continue;}
   cloud.put(k,row);
  }
  Set<String> names=new LinkedHashSet<>(local.keySet());
  names.addAll(cloud.keySet());
  for(String key:names){
   BackupCancellation.check();
   if(++visited>100000)throw new IOException("Sync limit: 100,000 items per run");
   Local phone=local.get(key);
   CloudDrive.RemoteItem remote=cloud.get(key);
   String name=phone==null?remote.name:phone.name;
   String relative=path+name;
   if(clashNames.remove(key)){conflict(relative,"Two names differ only by capital letters; rename one. Skipped.");continue;}
   if(reserved(name)||BackupRules.excluded(relative,pair.exclude)){excluded++;continue;}
   boolean phoneFolder=phone!=null&&phone.directory();
   boolean cloudFolder=remote!=null&&remote.folder;
   if((phone!=null&&!phoneFolder&&(remote!=null||direction!=SyncDirection.DOWNLOAD))
    ||(remote!=null&&!cloudFolder&&direction!=SyncDirection.UPLOAD&&remote.supported))filesChecked++;
   progress.accept(this,relative);
   try{BackupRules.validateName(name);}
   catch(IllegalArgumentException error){conflict(relative,"Unsupported file name.");continue;}
   if(remote!=null&&!name.equals(remote.name)){conflict(relative,"Names differ by case.");continue;}
   if((phoneFolder||cloudFolder)&&phone!=null&&remote!=null&&phoneFolder!=cloudFolder){
    conflict(relative,"A file and folder share the same name.");
    continue;
   }
   if(phoneFolder||cloudFolder){
    if(direction==SyncDirection.NEWEST&&pair.syncDeletions&&(phone==null)!=(remote==null)){
     Map<String,JSONObject> known=Store.under(context,pairId,relative+"/");
     if(!known.isEmpty()&&(phone==null?deleteCloudFolder(remote,relative,known):deletePhoneFolder(remoteFolderId,phone,relative,known)))continue;
    }
    if(remote==null&&direction==SyncDirection.DOWNLOAD)continue;
    if(phone==null&&direction==SyncDirection.UPLOAD)continue;
    Uri child=phone!=null?phone.uri:makeDocument(parent,Document.MIME_TYPE_DIR,name);
    String destination=remote!=null?remote.id:drive.ensureFolder(remoteFolderId,name);
    walk(child,destination,relative+"/",depth+1);
    continue;
   }
   if(remote!=null&&!remote.supported){
    conflict(relative,remote.unsupportedReason!=null?remote.unsupportedReason:"Unsupported "+drive.providerName()+" item.");
    continue;
   }
   try{syncFile(parent,remoteFolderId,name,relative,phone,remote);}
   catch(CloudDrive.HttpError e){
    if(!e.conflict)throw e;
    conflict(relative,drive.providerName()+" changed during sync. Retry after reviewing both copies.");
   }
   catch(BackupRules.FileTooLarge e){
    excluded++;
    Store.history(context,pairId,relative+": skipped (over "+BackupRules.MAX_FILE_MIB+" MiB).");
   }
   catch(LocalConflict e){conflict(relative,e.getMessage());}
  }
 }

 private void syncFile(Uri parent,String remoteFolderId,String name,String path,Local phone,CloudDrive.RemoteItem cloud)throws Exception{
  if(cloud==null&&direction==SyncDirection.DOWNLOAD)return;
  if(phone==null&&direction==SyncDirection.UPLOAD)return;
  JSONObject receipt=Store.receipt(context,pairId,path);
  boolean cloudUnchanged=cloud!=null&&receipt!=null&&!cloud.versionTag.isEmpty()
   &&cloud.id.equals(receipt.optString("id"))&&cloud.versionTag.equals(receipt.optString("tag"))
   &&cloud.size==receipt.optLong("size",-2);
  boolean phoneUnchanged=phone!=null&&receipt!=null&&phone.modified>0
   &&phone.modified==receipt.optLong("phoneModified",-1)&&phone.size==receipt.optLong("size",-2);
  switch(pair.syncDeletions?direction.deletion(phone!=null,cloud!=null,receipt!=null,phoneUnchanged,cloudUnchanged):SyncDirection.Deletion.NONE){
   case DELETE_CLOUD: deleteCloud(remoteFolderId,name,path,cloud,receipt);return;
   case DELETE_PHONE: deletePhone(remoteFolderId,name,path,phone);return;
   default: break;
  }
  if(receipt!=null&&(phone==null)!=(cloud==null)&&direction==SyncDirection.NEWEST)
   Store.history(context,pairId,path+": restored because it changed after being deleted on the other side.");
  // Fast path: neither side changed since the last sync, so skip reading and hashing the file.
  if(phoneUnchanged&&cloudUnchanged){unchanged++;return;}
  if(phone!=null)BackupRules.requireFileSize(phone.size);
  if(cloud!=null)BackupRules.requireFileSize(cloud.size);
  File local=null;
  try{
   CloudDrive.LocalDigest phoneDigest=null;
   if(phone!=null){
    boolean needFingerprint=cloud!=null&&!cloud.fingerprint.isEmpty()&&cloud.size==phone.size;
    phoneDigest=digestPhone(phone.uri,needFingerprint&&drive.wantsQuickXor(),needFingerprint&&drive.wantsMd5());
   }
   String localHash=phoneDigest==null?null:phoneDigest.sha256;
   CloudBackup backend=new CloudBackup(context,drive,pair,remoteFolderId,path,name);
   if(phone!=null&&direction!=SyncDirection.DOWNLOAD&&backend.hasCheckpoint(name,localHash)){
    local=stageLocal(phone.uri,localHash);
    VersionedBackup.Item resumed=backend.resumeCreate(name,local,localHash);
    if(resumed!=null){remember(path,name,resumed,localHash,phone.modified);uploaded++;return;}
   }
   VersionedBackup.Item remote=cloud==null?null:cloud.toBackupItem();
   String remoteHash=null;
   if(phone!=null&&remote!=null){
    remoteHash=receipt!=null&&!remote.tag.isEmpty()&&remote.id.equals(receipt.optString("id"))
     &&remote.tag.equals(receipt.optString("tag"))&&remote.size==receipt.optLong("size",-1)
     ?receipt.getString("hash"):drive.matchesFingerprint(cloud,phoneDigest)?localHash:backend.hash(remote);
   }
   boolean same=localHash!=null&&localHash.equals(remoteHash)&&phoneDigest.size==remote.size;
   long cloudModified=cloud==null?0:cloud.modifiedMillis;
   SyncDirection.Action action=direction.choose(phone!=null,remote!=null,same,phone==null?0:phone.modified,cloudModified);
   switch(action){
    case SKIP: return;
    case CONFLICT: conflict(path,"Different copies. Check their modification times before replacing either.");return;
    case UNCHANGED: remember(path,name,remote,localHash,phone.modified);unchanged++;return;
    case UPLOAD:
     BackupCancellation.check();
     if(!stillSame(phone))throw new LocalConflict("Phone file changed during sync.");
     if(local==null)local=stageLocal(phone.uri,localHash);
     VersionedBackup.Item saved;
     if(remote==null)saved=backend.create(name,local,localHash);
     else{
      requireTag(remote);
      backend.archive(remote,remoteHash);
      BackupCancellation.check();
      if(!stillSame(phone))throw new LocalConflict("Phone file changed while the "+drive.providerName()+" copy was archived.");
      saved=backend.update(remote,local);
      if(!remote.id.equals(saved.id))throw new IOException(drive.providerName()+" file identity changed");
     }
     remember(path,name,saved,localHash,phone.modified);
     uploaded++;
     return;
    case DOWNLOAD:
     download(parent,remoteFolderId,name,path,phone,localHash,remote);
     downloaded++;
   }
  }finally{if(local!=null)local.delete();}
 }

 private CloudDrive.LocalDigest digestPhone(Uri uri,boolean needQuickXor,boolean needMd5)throws Exception{
  MessageDigest sha=MessageDigest.getInstance("SHA-256");
  MessageDigest md5=needMd5?MessageDigest.getInstance("MD5"):null;
  QuickXorHash qx=needQuickXor?new QuickXorHash():null;
  long size=0;
  try(InputStream input=resolver.openInputStream(uri)){
   if(input==null)throw new IOException("Cannot read phone file");
   byte[] buffer=new byte[65536];int count;
   while((count=input.read(buffer))!=-1){
    BackupCancellation.check();
    size+=count;BackupRules.requireFileSize(size);
    sha.update(buffer,0,count);
    if(md5!=null)md5.update(buffer,0,count);
    if(qx!=null)qx.update(buffer,0,count);
   }
  }
  return new CloudDrive.LocalDigest(hex(sha.digest()),qx==null?null:qx.digest(),md5==null?null:hex(md5.digest()),size);
 }

 private static String hex(byte[] bytes){
  StringBuilder out=new StringBuilder(bytes.length*2);
  for(byte v:bytes)out.append(String.format(Locale.ROOT,"%02x",v&255));
  return out.toString();
 }

 private File stageLocal(Uri uri,String expectedHash)throws Exception{
  File local=File.createTempFile("sync-phone-",".tmp",context.getCacheDir());
  boolean ok=false;
  try{
   String stagedHash;
   try(InputStream input=resolver.openInputStream(uri);OutputStream output=new FileOutputStream(local)){
    if(input==null)throw new IOException("Cannot read phone file");
    stagedHash=BackupRules.hash(input,output);
   }
   if(!expectedHash.equals(stagedHash))throw new LocalConflict("Phone file changed during sync.");
   ok=true;
   return local;
  }finally{if(!ok)local.delete();}
 }

 /** Cheap check that the phone file was not edited meanwhile (size and time), instead of re-reading it. */
 private boolean stillSame(Local phone){
  String[] columns={Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED};
  try(android.database.Cursor row=resolver.query(phone.uri,columns,null,null,null)){
   return row!=null&&row.moveToFirst()&&row.getLong(0)==phone.size&&row.getLong(1)==phone.modified;
  }catch(Exception error){return false;}
 }

 private void remember(String path,String name,VersionedBackup.Item item,String hash,long phoneModified)throws Exception{
  Store.receipt(context,pairId,path,name,new VersionedBackup.Receipt(item.id,item.tag,hash,item.size),phoneModified);
 }

 private boolean deleteCloudFolder(CloudDrive.RemoteItem folder,String relative,Map<String,JSONObject> known)throws Exception{
  List<Object[]> files=new ArrayList<>();
  if(!collectCloud(folder.id,relative+"/",known,files,0))return false;
  for(Object[] entry:files){
   BackupCancellation.check();
   CloudDrive.RemoteItem cloud=(CloudDrive.RemoteItem)entry[0];
   JSONObject receipt=(JSONObject)entry[1];
   String path=receipt.getString("path"),name=receipt.optString("name",cloud.name);
   String parentId=cloud.parentId.isEmpty()?folder.id:cloud.parentId;
   new CloudBackup(context,drive,pair,parentId,path,name).archive(cloud.toBackupItem(),receipt.getString("hash"));
  }
  drive.delete(folder.id,null);
  for(Object[] entry:files)Store.forget(context,pairId,((JSONObject)entry[1]).getString("path"));
  Store.history(context,pairId,relative+"/: folder deleted from "+drive.providerName()+" ("+files.size()+" files kept in Version history).");
  deleted++;
  return true;
 }

 private boolean collectCloud(String folderId,String prefix,Map<String,JSONObject> known,List<Object[]> out,int depth)throws Exception{
  if(depth>32)return false;
  for(CloudDrive.RemoteItem row:drive.children(folderId)){
   String path=prefix+row.name;
   if(row.folder){if(!collectCloud(row.id,path+"/",known,out,depth+1))return false;continue;}
   JSONObject receipt=known.get(path);
   if(receipt==null||!row.id.equals(receipt.optString("id"))||!row.versionTag.equals(receipt.optString("tag"))
    ||row.size!=receipt.optLong("size",-2))return false;
   out.add(new Object[]{row,receipt});
  }
  return true;
 }

 private boolean deletePhoneFolder(String remoteFolderId,Local folder,String relative,Map<String,JSONObject> known)throws Exception{
  List<Object[]> files=new ArrayList<>();
  if(!collectPhone(folder.uri,relative+"/",known,files,0))return false;
  for(Object[] entry:files){
   BackupCancellation.check();
   Local phone=(Local)entry[0];JSONObject receipt=(JSONObject)entry[1];
   File copy=File.createTempFile("sync-deleted-",".tmp",context.getCacheDir());
   try{
    String hash;
    try(InputStream input=resolver.openInputStream(phone.uri);OutputStream output=new FileOutputStream(copy)){
     if(input==null)throw new IOException("Cannot read phone file");
     hash=BackupRules.hash(input,output);
    }
    new CloudBackup(context,drive,pair,remoteFolderId,receipt.getString("path"),phone.name).archiveLocal(copy,hash);
   }finally{copy.delete();}
  }
  if(!DocumentsContract.deleteDocument(resolver,folder.uri))throw new LocalConflict("Phone storage could not delete the folder.");
  for(Object[] entry:files)Store.forget(context,pairId,((JSONObject)entry[1]).getString("path"));
  Store.history(context,pairId,relative+"/: folder deleted on the phone because it was deleted on "+drive.providerName()+" ("+files.size()+" files kept in Version history).");
  deleted++;
  return true;
 }

 private boolean collectPhone(Uri folder,String prefix,Map<String,JSONObject> known,List<Object[]> out,int depth)throws Exception{
  if(depth>32)return false;
  for(Local child:localChildren(folder).values()){
   String path=prefix+child.name;
   if(child.directory()){if(!collectPhone(child.uri,path+"/",known,out,depth+1))return false;continue;}
   JSONObject receipt=known.get(path);
   if(receipt==null||child.modified<=0||child.modified!=receipt.optLong("phoneModified",-1)||child.size!=receipt.optLong("size",-2))return false;
   out.add(new Object[]{child,receipt});
  }
  return true;
 }

 private void deleteCloud(String remoteFolderId,String name,String path,CloudDrive.RemoteItem cloud,JSONObject receipt)throws Exception{
  BackupCancellation.check();
  VersionedBackup.Item remote=cloud.toBackupItem();
  new CloudBackup(context,drive,pair,remoteFolderId,path,name).archive(remote,receipt.getString("hash"));
  drive.delete(remote.id,remote.tag);
  Store.forget(context,pairId,path);
  Store.history(context,pairId,path+": deleted from "+drive.providerName()+" (a copy is in Version history).");
  deleted++;
 }

 private void deletePhone(String remoteFolderId,String name,String path,Local phone)throws Exception{
  BackupCancellation.check();
  File copy=File.createTempFile("sync-deleted-",".tmp",context.getCacheDir());
  try{
   String hash;
   try(InputStream input=resolver.openInputStream(phone.uri);OutputStream output=new FileOutputStream(copy)){
    if(input==null)throw new IOException("Cannot read phone file");
    hash=BackupRules.hash(input,output);
   }
   new CloudBackup(context,drive,pair,remoteFolderId,path,name).archiveLocal(copy,hash);
  }finally{copy.delete();}
  String kept=HISTORY_PREFIX+UUID.randomUUID()+"-"+phone.name.substring(0,Math.min(80,phone.name.length()));
  if(DocumentsContract.renameDocument(resolver,phone.uri,kept)==null)throw new LocalConflict("Phone storage could not move the deleted file aside.");
  Store.forget(context,pairId,path);
  Store.history(context,pairId,path+": deleted on "+drive.providerName()+"; phone copy kept as "+kept+" and in Version history.");
  deleted++;
 }

 private void requireTag(VersionedBackup.Item item)throws LocalConflict{
  if(item.tag.isEmpty())throw new LocalConflict(drive.providerName()+" did not return a file version. No file replaced.");
 }

 private void verifyCloud(String remoteFolderId,String name,VersionedBackup.Item remote)throws Exception{
  CloudDrive.RemoteItem actual=drive.item(remoteFolderId,name);
  if(actual==null||!remote.id.equals(actual.id)||!remote.tag.equals(actual.versionTag)
   ||remote.size!=actual.size)throw new LocalConflict(drive.providerName()+" file changed during download.");
 }

 private void download(Uri parent,String remoteFolderId,String name,String path,Local original,String originalHash,
  VersionedBackup.Item remote)throws Exception{
  requireTag(remote);
  if(remote.size<0)throw new LocalConflict(drive.providerName()+" did not report a valid file size.");
  BackupRules.requireFileSize(remote.size);
  File staged=File.createTempFile("sync-cloud-",".tmp",context.getCacheDir());
  Uri temporary=null;
  boolean published=false;
  try{
   String hash=drive.download(remote.id,staged);
   if(staged.length()!=remote.size)throw new LocalConflict("Downloaded size differs from "+drive.providerName()+".");
   temporary=makeDocument(parent,"application/octet-stream",TEMP_PREFIX+UUID.randomUUID());
   try(InputStream input=new FileInputStream(staged);OutputStream output=resolver.openOutputStream(temporary,"wt")){
    if(output==null)throw new IOException("Cannot stage the download on the phone");
    BackupRules.hash(input,output);
   }
   if(!hash.equals(hash(temporary)))throw new IOException("Phone download could not be verified");
   verifyCloud(remoteFolderId,name,remote);
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
