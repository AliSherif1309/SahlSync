package com.aly.sahlsync;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

final class LanFolderSync {
 private static final int MAX_ITEMS=5000;
 private final Context context;
 private final LanPeer peer;
 private final Map<String,LocalFile> phoneFiles=new TreeMap<>();
 private final Map<String,String> names=new HashMap<>();
 private final Consumer<String> progress;
 private int visited,skipped;

 LanFolderSync(Context context,LanPeer peer,Consumer<String> progress){
  this.context=context;
  this.peer=peer;
  this.progress=progress;
 }

 static final class Result {
  final JSONObject baseline;
  final String summary;
  Result(JSONObject baseline,String summary){this.baseline=baseline;this.summary=summary;}
 }

 private static final class LocalFile {
  final DocumentFile document;
  final String hash;
  LocalFile(DocumentFile document,String hash){this.document=document;this.hash=hash;}
 }

 Result run(Uri uri,JSONObject baseline)throws Exception{
  DocumentFile folder=DocumentFile.fromTreeUri(context,uri);
  if(folder==null||!folder.isDirectory()||!FolderSync.writable(context.getContentResolver(),uri))
   throw new IOException("Choose a writable phone folder again.");
  progress.accept("Checking phone files…");
  scan(folder,"",0);
  progress.accept("Checking PC files…");
  JSONObject inventory=peer.inventory(),pcFiles=inventory.getJSONObject("files");
  skipped+=inventory.getInt("skipped");
  if(pcFiles.length()>MAX_ITEMS)throw new IOException("PC folder has too many files.");
  Set<String> paths=new TreeSet<>(phoneFiles.keySet());
  for(Iterator<String> it=pcFiles.keys();it.hasNext();){
   String path=it.next();
   validate(path);
   registerName(path);
   paths.add(path);
  }
  if(paths.size()>MAX_ITEMS)throw new IOException("Combined folders have more than 5,000 files.");
  JSONObject updated=new JSONObject(baseline.toString());
  int sent=0,received=0,checked=0,unchanged=0,conflicts=0;
  List<String> examples=new ArrayList<>();
  for(String path:paths){
   BackupCancellation.check();
   progress.accept(checked+" / "+paths.size()+" files checked · "+(sent+received)+" synced\n"+path);
   LocalFile phone=phoneFiles.get(path);
   JSONObject pc=pcFiles.optJSONObject(path);
   String local=phone==null?"":phone.hash,remote=pc==null?"":pc.getString("hash");
   if(pc!=null&&(!remote.matches("[0-9a-f]{64}")||pc.getLong("size")<0))throw new IOException("Invalid PC file inventory.");
   String previous=baseline.optString(path);
   try{
    switch(action(local,remote,previous)){
     case UNCHANGED: updated.put(path,local);unchanged++;break;
     case SEND: upload(path,phone,remote);updated.put(path,local);sent++;break;
     case RECEIVE: download(folder,path,local,remote,pc.getLong("size"));updated.put(path,remote);received++;break;
     case CONFLICT: throw new LanPeer.Changed();
    }
   }catch(LanPeer.Changed conflict){
    conflicts++;
    if(examples.size()<5)examples.add(path);
   }
   checked++;
  }
  String summary=checked+" files checked · "+(sent+received)+" synced ("+sent+" sent, "+received+" received)"
   +"\n"+unchanged+" unchanged · "+skipped+" skipped · "+conflicts+" conflicts";
  if(!examples.isEmpty())summary+="\nReview on both devices:\n"+String.join("\n",examples);
  return new Result(updated,summary);
 }

 enum Action { UNCHANGED, SEND, RECEIVE, CONFLICT }

 static Action action(String phone,String pc,String lastSynced){
  if(phone.equals(pc))return Action.UNCHANGED;
  if(pc.isEmpty())return Action.SEND;
  if(phone.isEmpty())return Action.RECEIVE;
  if(pc.equals(lastSynced))return Action.SEND;
  if(phone.equals(lastSynced))return Action.RECEIVE;
  return Action.CONFLICT;
 }

 private void scan(DocumentFile folder,String prefix,int depth)throws Exception{
  if(depth>=32)throw new IOException("Phone folder nesting limit reached.");
  // Query directly so unavailable folders cannot look empty.
  Uri parent=folder.getUri();
  Uri children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(parent,
   android.provider.DocumentsContract.getDocumentId(parent));
  String[] columns={"document_id","_display_name","mime_type","_size"};
  try(android.database.Cursor rows=context.getContentResolver().query(children,columns,null,null,null)){
   if(rows==null)throw new IOException("Could not read the phone folder.");
   while(rows.moveToNext()){
    BackupCancellation.check();
    if(++visited>MAX_ITEMS)throw new IOException("Phone folder has more than 5,000 items.");
    String name=rows.getString(1);
    if(name==null)throw new IOException("Phone file name is unavailable.");
    if(FolderSync.reserved(name))continue;
    String path=prefix+name;
    try{validate(path);}catch(IllegalArgumentException error){skipped++;continue;}
    registerName(path);
    DocumentFile item=DocumentFile.fromSingleUri(context,android.provider.DocumentsContract.buildDocumentUriUsingTree(parent,rows.getString(0)));
    if(item==null)throw new IOException("Could not access a phone file.");
    if("vnd.android.document/directory".equals(rows.getString(2)))scan(item,path+"/",depth+1);
    else{
     if(rows.getLong(3)>BackupRules.MAX_FILE_BYTES){skipped++;continue;}
     try{
      if(phoneFiles.put(path,new LocalFile(item,hash(item,null)))!=null)throw new IOException("Duplicate file name in phone folder.");
     }catch(BackupRules.FileTooLarge error){skipped++;}
    }
   }
  }
 }

 private void registerName(String path)throws IOException{
  String prefix="";
  for(String part:path.split("/")){
   prefix=prefix.isEmpty()?part:prefix+"/"+part;
   String previous=names.putIfAbsent(prefix.toLowerCase(Locale.ROOT),prefix);
   if(previous!=null&&!previous.equals(prefix))throw new IOException("Names differ only by case. Rename one copy and retry.");
  }
 }

 private static void validate(String path){
  String[] parts=path.split("/",-1);
  if(parts.length>32)throw new IllegalArgumentException("Deep file path");
  for(String part:parts){
   if(part.isEmpty()||part.endsWith(".")||part.endsWith(" ")||part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(\\..*)?")
    ||part.matches("(?s).*[\\x00-\\x1f\\\\:*?\"<>|].*")||part.getBytes(StandardCharsets.UTF_8).length>240
    ||FolderSync.reserved(part))throw new IllegalArgumentException("Unsupported file name");
  }
 }

 private String hash(DocumentFile document,OutputStream copy)throws Exception{
  try(InputStream input=context.getContentResolver().openInputStream(document.getUri())){
   if(input==null)throw new IOException("Could not open a phone file.");
   return BackupRules.hash(input,copy);
  }
 }

 private void upload(String path,LocalFile phone,String remote)throws Exception{
  File source=File.createTempFile("lan-send-",".tmp",context.getCacheDir());
  try{
   try(OutputStream output=new FileOutputStream(source)){
    if(!phone.hash.equals(hash(phone.document,output)))throw new LanPeer.Changed();
   }
   peer.upload(path,source,phone.hash,remote.isEmpty()?"missing":remote);
  }finally{source.delete();}
 }

 private void download(DocumentFile folder,String path,String local,String remote,long size)throws Exception{
  BackupRules.requireFileSize(size);
  File source=File.createTempFile("lan-receive-",".tmp",context.getCacheDir());
  try{
   peer.download(path,source,remote,size);
   String[] parts=path.split("/");
   for(int i=0;i<parts.length-1;i++){
    DocumentFile child=folder.findFile(parts[i]);
    if(child==null)child=folder.createDirectory(parts[i]);
    if(child==null||!child.isDirectory()||!parts[i].equals(child.getName()))throw new LanPeer.Changed();
    folder=child;
   }
   publish(folder,parts[parts.length-1],source,local,remote);
  }finally{source.delete();}
 }

 private void publish(DocumentFile folder,String name,File source,String local,String remote)throws Exception{
  DocumentFile temporary=folder.createFile("application/octet-stream","sahl-sync-staging-"+UUID.randomUUID());
  if(temporary==null)throw new IOException("Could not stage a phone file.");
  DocumentFile prior=null;
  boolean published=false;
  try{
   try(InputStream input=new FileInputStream(source);OutputStream output=context.getContentResolver().openOutputStream(temporary.getUri(),"w")){
    if(output==null||!remote.equals(BackupRules.hash(input,output)))throw new LanPeer.Changed();
   }
   if(!remote.equals(hash(temporary,null)))throw new LanPeer.Changed();
   DocumentFile current=folder.findFile(name);
   if(current!=null&&(local.isEmpty()||!current.isFile()||!local.equals(hash(current,null))))throw new LanPeer.Changed();
   if(current==null&&!local.isEmpty())throw new LanPeer.Changed();
   BackupCancellation.check();
   if(current!=null){
    if(!current.renameTo("sahl-sync-previous-"+UUID.randomUUID()+"-"+name))throw new IOException("Could not save the previous phone copy.");
    prior=current;
   }
   if(!temporary.renameTo(name)||!name.equals(temporary.getName()))throw new IOException("Could not publish the downloaded file.");
   published=true;
  }finally{
   if(!published){
    temporary.delete();
    if(prior!=null&&folder.findFile(name)==null)prior.renameTo(name);
   }
  }
 }
}
