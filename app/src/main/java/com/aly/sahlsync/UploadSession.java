package com.aly.sahlsync;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import org.json.*;

final class UploadSession {
 private static final int CHUNK=5*1024*1024;
 private final Graph graph;
 private final Context context;
 private final SharedPreferences checkpoints;
 private final String pair;

 UploadSession(Graph graph,Context context,String pair){
  this.graph=graph;
  this.context=context;
  this.pair=pair;
  checkpoints=context.getSharedPreferences("upload-checkpoints",Context.MODE_PRIVATE);
 }

 static final class ConnectionFailure extends IOException {
  ConnectionFailure(IOException cause){super("Upload connection interrupted",cause);}
 }

 JSONObject create(String path,File file)throws Exception{
  String hash;
  try(InputStream input=new FileInputStream(file)){hash=BackupRules.hash(input,null);}
  JSONObject resumed=resume(path,file,hash);
  if(resumed!=null)return resumed;
  JSONObject item=new JSONObject().put("@microsoft.graph.conflictBehavior","fail");
  JSONObject created=graph.json("POST",path+"/createUploadSession",new JSONObject().put("item",item));
  String url=created.getString("uploadUrl");
  JSONObject checkpoint=new JSONObject().put("identity",path+"\n"+hash).put("url",url);
  if(!checkpoints.edit().putString(pair,checkpoint.toString()).commit())
   throw new IOException("Could not save upload checkpoint");
  return send(url,file,0);
 }

 JSONObject resume(String path,File file,String hash)throws Exception{
  JSONObject checkpoint=new JSONObject(checkpoints.getString(pair,"{}"));
  if(!(path+"\n"+hash).equals(checkpoint.optString("identity")))return null;
  String url=checkpoint.optString("url");
  if(url.isEmpty())return null;
  long offset;
  try{
   offset=nextOffset(request(url,null,0,0,file.length()),file.length());
  }catch(Graph.HttpError error){
   if(error.status!=404&&error.status!=410)throw error;
   checkpoints.edit().remove(pair).apply();
   return null;
  }
  Store.history(context,pair,"OneDrive upload checkpoint: "+offset+" of "+file.length()+" bytes already accepted.");
  return send(url,file,offset);
 }

 private JSONObject send(String url,File file,long offset)throws Exception{
  long length=file.length();
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
     checkpoints.edit().remove(pair).apply();
     return response;
    }
    long next=nextOffset(response,length);
    if(next<=offset)throw new IOException("Upload did not advance");
    offset=next;
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
