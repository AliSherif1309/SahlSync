package com.aly.sahlsync;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

final class LanVersions {
 private final File folder;

 LanVersions(Context context,String fingerprint,String uri){
  String pair=UUID.nameUUIDFromBytes((fingerprint+"\n"+uri).getBytes(StandardCharsets.UTF_8)).toString();
  folder=new File(new File(context.getFilesDir(),"lan-versions"),pair);
 }

 interface Copy { void write(File destination)throws Exception; }

 void save(String path,String side,String hash,Copy copy)throws Exception{
  String id=UUID.nameUUIDFromBytes((path+"\n"+side+"\n"+hash).getBytes(StandardCharsets.UTF_8)).toString();
  File record=new File(folder,id);
  if(new File(record,"info.json").isFile()&&new File(record,"content").isFile())return;
  if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Could not open Version history.");
  File staging=Files.createTempDirectory(folder.toPath(),".staging-").toFile();
  try{
   File data=new File(staging,"content");
   copy.write(data);
   JSONObject info=new JSONObject().put("path",path).put("side",side).put("savedAt",System.currentTimeMillis()).put("hash",hash);
   try(FileOutputStream out=new FileOutputStream(new File(staging,"info.json"))){
    out.write(info.toString().getBytes(StandardCharsets.UTF_8));
   }
   if(!staging.renameTo(record))throw new IOException("Could not save the previous version.");
  }finally{
   new File(staging,"info.json").delete();
   new File(staging,"content").delete();
   staging.delete();
  }
 }

 List<Version> list()throws Exception{
  List<Version> versions=new ArrayList<>();
  File[] records=folder.listFiles();
  if(records==null)return versions;
  for(File entry:records){
   if(entry.getName().startsWith(".")||!new File(entry,"content").isFile())continue;
   JSONObject info=new JSONObject(new String(Files.readAllBytes(new File(entry,"info.json").toPath()),StandardCharsets.UTF_8));
   versions.add(new Version(entry.getName(),info.getString("path"),info.getString("side"),info.getLong("savedAt"),info.getString("hash")));
  }
  versions.sort(Comparator.comparingLong((Version version)->version.savedAt).reversed());
  return versions;
 }

 void export(Context context,android.net.Uri destination,Version version)throws Exception{
  File data=new File(new File(folder,version.id),"content");
  try(InputStream in=new FileInputStream(data)){
   if(!version.hash.equals(BackupRules.hash(in,null)))throw new IOException("Saved version could not be verified.");
  }
  try(InputStream in=new FileInputStream(data);OutputStream out=context.getContentResolver().openOutputStream(destination,"w")){
   if(out==null)throw new IOException("Could not open the selected destination.");
   BackupRules.hash(in,out);
  }
 }

 static final class Version {
  final String id,path,side,hash;
  final long savedAt;
  Version(String id,String path,String side,long savedAt,String hash){
   this.id=id;this.path=path;this.side=side;this.savedAt=savedAt;this.hash=hash;
  }
 }
}
