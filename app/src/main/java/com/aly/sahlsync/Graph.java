package com.aly.sahlsync;

import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class Graph {
 static final String ROOT="https://graph.microsoft.com/v1.0";
 interface TokenSource { String acquire(boolean forceRefresh)throws Exception; }
 interface AuthorizedRequest<T> { T send(String token)throws Exception; }

 static final class Authorization {
  private final TokenSource source;
  private String token;

  Authorization(TokenSource source){this.source=source;}

  <T> T send(AuthorizedRequest<T> request)throws Exception{
   BackupCancellation.check();
   if(token==null)token=source.acquire(false);
   try{return request.send(token);}
   catch(HttpError error){if(error.status!=401)throw error;}
   BackupCancellation.check();
   token=source.acquire(true);
   BackupCancellation.check();
   try{return request.send(token);}
   catch(HttpError error){
    if(error.status==401)throw new Auth.SignInRequired();
    throw error;
   }
  }
 }

 private final Authorization authorization;
 private UploadSession uploads;
 void enableResumableUploads(android.content.Context context,String pair,UploadSession.ProgressListener progress){uploads=new UploadSession(this,context,pair,progress);}
 Graph(TokenSource tokens){authorization=new Authorization(tokens);}
 static final class HttpError extends IOException {
  final int status;
  HttpError(int status){super("OneDrive returned HTTP "+status);this.status=status;}
 }
 JSONObject json(String method,String path,JSONObject body) throws Exception {
  return request(method,ROOT+path,body==null?null:body.toString().getBytes(StandardCharsets.UTF_8),"application/json");
 }
 JSONObject request(String method,String address,byte[] data,String type) throws Exception {
  return request(method,address,data,type,null);
 }
 JSONObject request(String method,String address,byte[] data,String type,String ifMatch)throws Exception{
  return authorization.send(token->sendJson(method,address,data,type,ifMatch,token));
 }
 private JSONObject sendJson(String method,String address,byte[] data,String type,String ifMatch,String token)throws Exception{
  BackupCancellation.check();
  URI uri=URI.create(address);
  if(!"https".equals(uri.getScheme())||!"graph.microsoft.com".equals(uri.getHost()))throw new IOException("Untrusted Graph address");
  HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
  try {
   c.setInstanceFollowRedirects(false); c.setConnectTimeout(30000); c.setReadTimeout(60000);
   c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);
   if(ifMatch!=null)c.setRequestProperty("If-Match",ifMatch);
   if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",type);c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){for(int offset=0;offset<data.length;offset+=65536){BackupCancellation.check();out.write(data,offset,Math.min(65536,data.length-offset));}}}
   int status=c.getResponseCode(); if(status<200||status>=300)throw new HttpError(status);
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>4*1024*1024)throw new IOException("Response too large");}
    return new JSONObject(out.size()==0?"{}":out.toString(StandardCharsets.UTF_8.name()));
   }
  }finally{c.disconnect();}
 }
 String contentHash(String itemId)throws Exception{
  String address=ROOT+"/me/drive/items/"+Uri.encode(itemId)+"/content";
  return authorization.send(token->RemoteContent.sha256(address,token));
 }
 JSONObject item(String parent,String name) throws Exception {return json("GET","/me/drive/items/"+Uri.encode(parent)+":/"+Uri.encode(name),null);}
 String folder(String parent,String name) throws Exception {
  BackupRules.validateName(name);
  try{return requireFolder(item(parent,name));}catch(HttpError e){if(e.status!=404)throw e;}
  JSONObject body=new JSONObject().put("name",name).put("folder",new JSONObject()).put("@microsoft.graph.conflictBehavior","fail");
  try{return requireFolder(json("POST","/me/drive/items/"+Uri.encode(parent)+"/children",body));}
  catch(HttpError e){if(e.status!=409)throw e;return requireFolder(item(parent,name));}
 }
 private String requireFolder(JSONObject item)throws Exception{if(!item.has("folder"))throw new IOException("A file blocks the destination folder");return item.getString("id");}
 String download(String itemId,File destination)throws Exception{
  String address=ROOT+"/me/drive/items/"+Uri.encode(itemId)+"/content";
  return authorization.send(token->RemoteContent.download(address,token,destination));
 }
 JSONObject resumeUpload(String parent,String name,File file,String hash)throws Exception{
  if(uploads==null||file.length()<=10L*1024*1024)return null;
  String path="/me/drive/items/"+Uri.encode(parent)+":/"+Uri.encode(name)+":";
  return uploads.resume(path,file,hash);
 }
 JSONObject upload(String parent,String name,File file) throws Exception {
  String path="/me/drive/items/"+Uri.encode(parent)+":/"+Uri.encode(name)+":";
  if(uploads!=null&&file.length()>10L*1024*1024)return uploads.create(path,file);
  return putFile(ROOT+path+"/content?%40microsoft.graph.conflictBehavior=fail",file,"0");
 }
 JSONObject updateFile(String id,String expectedTag,File file)throws Exception{
  if(expectedTag==null||expectedTag.isEmpty())throw new IOException("Missing file tag");
  return putFile(ROOT+"/me/drive/items/"+Uri.encode(id)+"/content",file,expectedTag);
 }
 private JSONObject putFile(String address,File file,String ifMatch)throws Exception{
  return authorization.send(token->sendFile(address,file,ifMatch,token));
 }
 private JSONObject sendFile(String address,File file,String ifMatch,String token)throws Exception{
  BackupCancellation.check();
  long size=file.length();
  BackupRules.requireFileSize(size);
  HttpURLConnection connection=(HttpURLConnection)URI.create(address).toURL().openConnection();
  try {
   connection.setInstanceFollowRedirects(false);
   connection.setConnectTimeout(30000);
   connection.setReadTimeout(60000);
   connection.setRequestMethod("PUT");
   connection.setRequestProperty("Authorization","Bearer "+token);
   connection.setRequestProperty("If-Match",ifMatch);
   connection.setRequestProperty("Content-Type","application/octet-stream");
   connection.setDoOutput(true);
   connection.setFixedLengthStreamingMode(size);
   try(InputStream input=new FileInputStream(file);OutputStream output=connection.getOutputStream()){
    byte[] buffer=new byte[65536];
    int count;
    while((count=input.read(buffer))!=-1){
     BackupCancellation.check();
     output.write(buffer,0,count);
    }
   }
   int status=connection.getResponseCode();
   if(status<200||status>=300)throw new HttpError(status);
   return UploadSession.readJson(connection);
  }finally{connection.disconnect();}
 }
 JSONArray folders(String parent)throws Exception{
  JSONArray all=children(parent),result=new JSONArray();for(int i=0;i<all.length();i++)if(all.getJSONObject(i).has("folder"))result.put(all.get(i));return result;
 }
 JSONArray children(String parent)throws Exception{
  JSONArray result=new JSONArray();String path="/me/drive/items/"+Uri.encode(parent)+"/children?$select=id,name,folder,file,size,createdDateTime,lastModifiedDateTime,eTag,remoteItem,package&$top=200";
  int pages=0;
  while(path!=null){
   if(++pages>50)throw new IOException("Too many folders; choose a smaller folder");
   JSONObject page=json("GET",path,null);JSONArray items=page.getJSONArray("value");
   for(int i=0;i<items.length();i++)result.put(items.getJSONObject(i));
   String next=page.optString("@odata.nextLink","");
   if(!next.isEmpty()&&!next.startsWith(ROOT+"/"))throw new IOException("Untrusted next page");
   path=next.isEmpty()?null:next.substring(ROOT.length());
  }return result;
 }
}
