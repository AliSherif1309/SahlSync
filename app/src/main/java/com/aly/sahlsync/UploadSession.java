package com.aly.sahlsync;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import org.json.*;

final class UploadSession {
 private static final int CHUNK=5*1024*1024;
 interface ProgressListener { void onProgress(long accepted,long total,boolean resumed); }
 private final ProgressListener progress;
 private final Graph graph;
 private final Context context;
 private final SharedPreferences checkpoints;
 private final String pair;

 UploadSession(Graph graph,Context context,String pair,ProgressListener progress){
  this.graph=graph;
  this.progress=progress;
  this.context=context;
  this.pair=pair;
  checkpoints=context.getSharedPreferences("upload-checkpoints",Context.MODE_PRIVATE);
 }

 static final class ConnectionFailure extends IOException {
  ConnectionFailure(IOException cause){super("Upload connection interrupted",cause);}
 }

 private String checkpointKey(String path){return pair+":"+path;}

 boolean hasCheckpoint(String path,String hash){
  String expected=path+"\n"+hash;
  if(matchesCheckpoint(checkpointKey(path),expected))return true;
  return matchesCheckpoint(pair,expected);
 }

 private boolean matchesCheckpoint(String key,String expected){
  try{
   JSONObject checkpoint=new JSONObject(checkpoints.getString(key,"{}"));
   return expected.equals(checkpoint.optString("identity"))&&!checkpoint.optString("url").isEmpty();
  }catch(Exception ignored){return false;}
 }

 JSONObject create(String path,File file)throws Exception{
  String hash;
  try(InputStream input=new FileInputStream(file)){hash=BackupRules.hash(input,null);}
  return create(path,file,hash);
 }

 JSONObject create(String path,File file,String hash)throws Exception{
  JSONObject resumed=resume(path,file,hash);
  if(resumed!=null)return resumed;
  JSONObject item=new JSONObject().put("@microsoft.graph.conflictBehavior","fail");
  JSONObject created=graph.json("POST",path+"/createUploadSession",new JSONObject().put("item",item));
  String url=created.getString("uploadUrl");
  String key=checkpointKey(path);
  JSONObject checkpoint=new JSONObject().put("identity",path+"\n"+hash).put("url",url);
  if(!checkpoints.edit().putString(key,checkpoint.toString()).commit())
   throw new IOException("Could not save upload checkpoint");
  return send(url,file,0,false,key);
 }

 /** Replaces an existing large file in chunks. If-Match makes OneDrive reject it if the file changed meanwhile. */
 JSONObject replace(String itemId,String eTag,File file)throws Exception{
  byte[] body=new JSONObject().put("item",new JSONObject().put("@microsoft.graph.conflictBehavior","replace"))
   .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  JSONObject created=graph.request("POST",Graph.ROOT+"/me/drive/items/"+android.net.Uri.encode(itemId)+"/createUploadSession",
   body,"application/json",eTag);
  return send(created.getString("uploadUrl"),file,0,false,null);
 }

 JSONObject resume(String path,File file,String hash)throws Exception{
  String key=checkpointKey(path);
  JSONObject checkpoint=new JSONObject(checkpoints.getString(key,"{}"));
  if(!(path+"\n"+hash).equals(checkpoint.optString("identity"))){
   key=pair;
   checkpoint=new JSONObject(checkpoints.getString(key,"{}"));
   if(!(path+"\n"+hash).equals(checkpoint.optString("identity")))return null;
  }
  String url=checkpoint.optString("url");
  if(url.isEmpty())return null;
  long offset;
  try{
   offset=nextOffset(request(url,null,0,0,file.length()),file.length());
  }catch(Graph.HttpError error){
   if(error.status!=404&&error.status!=410)throw error;
   checkpoints.edit().remove(key).apply();
   return null;
  }
  Store.history(context,pair,"OneDrive upload checkpoint: "+offset+" of "+file.length()+" bytes already accepted.");
  return send(url,file,offset,offset>0,key);
 }

 private JSONObject send(String url,File file,long offset,boolean resumed,String checkpointKey)throws Exception{
  long length=file.length();
  progress.onProgress(offset,length,resumed);
  try(RandomAccessFile input=new RandomAccessFile(file,"r")){
   byte[] bytes=new byte[CHUNK];
   while(offset<length){
    BackupCancellation.check();
    int count=(int)Math.min(bytes.length,length-offset);
    input.seek(offset);
    input.readFully(bytes,0,count);
    JSONObject response=request(url,bytes,count,offset,length);
    if(response.has("id")){
     if(!response.has("file")||response.optLong("size",-1)!=length)
      throw new IOException("OneDrive returned an incomplete upload result");
     if(checkpointKey!=null)checkpoints.edit().remove(checkpointKey).apply();
     progress.onProgress(length,length,resumed);
     return response;
    }
    long next=nextOffset(response,length);
    if(next<=offset)throw new IOException("Upload did not advance");
    offset=next;
    progress.onProgress(offset,length,resumed);
   }
  }
  throw new IOException("Upload session did not return the completed file");
 }

 private static long nextOffset(JSONObject response,long length)throws Exception{
  String range=response.getJSONArray("nextExpectedRanges").getString(0);
  int separator=range.indexOf('-');
  if(separator<=0)throw new IOException("Invalid upload range");
  long offset=Long.parseLong(range.substring(0,separator));
  if(offset<0||offset>=length)throw new IOException("Upload offset out of bounds");
  return offset;
 }

 private static JSONObject request(String url,byte[] bytes,int count,long offset,long length)throws Exception{
  BackupCancellation.check();
  URI uri=URI.create(url);
  if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)
   throw new IOException("Invalid upload session address");
  HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
  try{
   connection.setInstanceFollowRedirects(false);
   connection.setConnectTimeout(30000);
   connection.setReadTimeout(60000);
   if(bytes!=null){
    connection.setRequestMethod("PUT");
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(count);
    connection.setRequestProperty("Content-Type","application/octet-stream");
    connection.setRequestProperty("Content-Range","bytes "+offset+"-"+(offset+count-1)+"/"+length);
    try(OutputStream output=connection.getOutputStream()){
     for(int index=0;index<count;index+=65536){
      BackupCancellation.check();
      output.write(bytes,index,Math.min(65536,count-index));
     }
    }
   }
   int status=connection.getResponseCode();
   if(status<200||status>=300)throw new Graph.HttpError(status);
   return readJson(connection);
  }catch(Graph.HttpError error){throw error;}
   catch(IOException error){throw new ConnectionFailure(error);}
  finally{connection.disconnect();}
 }

 static JSONObject readJson(HttpURLConnection connection)throws Exception{
  try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];
   int count;
   while((count=input.read(buffer))!=-1){
    if(output.size()+count>4*1024*1024)throw new IOException("Response too large");
    output.write(buffer,0,count);
   }
   return new JSONObject(output.toString("UTF-8"));
  }
 }
}
