package com.aly.sahlsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.json.*;

/** CloudDrive implementation for Google Drive REST API v3. */
final class GoogleDriveDrive implements CloudDrive {
 private static final String API="https://www.googleapis.com/drive/v3";
 private static final String UPLOAD_API="https://www.googleapis.com/upload/drive/v3";
 private static final String FOLDER_MIME="application/vnd.google-apps.folder";
 private static final String FIELDS="id,name,mimeType,size,modifiedTime,md5Checksum,version,parents";
 private static final int CHUNK=5*1024*1024; // 5 MiB (multiple of 256 KiB required by Google Drive)

 interface TokenSource { String acquire(boolean forceRefresh)throws Exception; }

 private final TokenSource tokens;
 private final Context context;
 private final String pairId;
 private final UploadSession.ProgressListener progress;
 private String cachedToken;

 GoogleDriveDrive(Context context,String accountId,String pairId,UploadSession.ProgressListener progress){
  this(context,forceRefresh->GoogleAuth.token(context,accountId,forceRefresh),pairId,progress);
 }

 GoogleDriveDrive(Context context,TokenSource tokens,String pairId,UploadSession.ProgressListener progress){
  this.context=context;
  this.tokens=tokens;
  this.pairId=pairId;
  this.progress=progress;
 }

 @Override public String providerName(){return "Google Drive";}
 @Override public boolean wantsQuickXor(){return false;}
 @Override public boolean wantsMd5(){return true;}

 @Override public boolean matchesFingerprint(RemoteItem item,LocalDigest local){
  if(item==null||local==null||local.md5==null||item.fingerprint.isEmpty())return false;
  return item.size==local.size&&item.fingerprint.equalsIgnoreCase(local.md5);
 }

 static String escapeQuery(String value){
  return value.replace("\\","\\\\").replace("'","\\'");
 }

 static long parseRangeNextOffset(String rangeHeader){
  if(rangeHeader==null||rangeHeader.isEmpty())return 0;
  int dash=rangeHeader.indexOf('-');
  if(dash<0||dash+1>=rangeHeader.length())return 0;
  try{return Long.parseLong(rangeHeader.substring(dash+1).trim())+1;}
  catch(NumberFormatException e){return 0;}
 }

 private interface Request<T> { T send(String token)throws Exception; }

 private <T> T authorized(Request<T> req)throws Exception{
  BackupCancellation.check();
  if(cachedToken==null)cachedToken=tokens.acquire(false);
  try{return req.send(cachedToken);}
  catch(HttpError e){if(e.status!=401)throw e;}
  BackupCancellation.check();
  cachedToken=tokens.acquire(true);
  BackupCancellation.check();
  try{return req.send(cachedToken);}
  catch(HttpError e){
   if(e.status==401)throw new AuthRequired("Sign in to Google Drive to continue",e);
   throw e;
  }
 }

 RemoteItem rootFolder()throws Exception{
  return authorized(token->toRemoteItem(sendJson("GET",API+"/files/root?fields="+Uri.encode(FIELDS)+"&supportsAllDrives=true",null,token)));
 }

 @Override public RemoteItem item(String parentId,String name)throws Exception{
  BackupRules.validateName(name);
  String q="'"+escapeQuery(parentId)+"' in parents and name = '"+escapeQuery(name)+"' and trashed = false";
  String url=API+"/files?q="+Uri.encode(q)+"&fields="+Uri.encode("files("+FIELDS+")")+"&pageSize=2&supportsAllDrives=true&includeItemsFromAllDrives=true";
  JSONObject res=authorized(token->sendJson("GET",url,null,token));
  JSONArray files=res.optJSONArray("files");
  if(files==null||files.length()==0)return null;
  if(files.length()>1)throw new IOException("Multiple Google Drive items share the name "+name);
  return toRemoteItem(files.getJSONObject(0));
 }

 @Override public List<RemoteItem> children(String parentId)throws Exception{
  List<RemoteItem> result=new ArrayList<>();
  String q="'"+escapeQuery(parentId)+"' in parents and trashed = false";
  String pageToken=null;
  int pages=0;
  do{
   if(++pages>500)throw new IOException("Too many items in folder");
   StringBuilder url=new StringBuilder(API).append("/files?q=").append(Uri.encode(q))
    .append("&fields=").append(Uri.encode("nextPageToken,files("+FIELDS+")"))
    .append("&pageSize=200&supportsAllDrives=true&includeItemsFromAllDrives=true");
   if(pageToken!=null)url.append("&pageToken=").append(Uri.encode(pageToken));
   String finalUrl=url.toString();
   JSONObject page=authorized(token->sendJson("GET",finalUrl,null,token));
   JSONArray files=page.optJSONArray("files");
   if(files!=null)for(int i=0;i<files.length();i++)result.add(toRemoteItem(files.getJSONObject(i)));
   pageToken=page.optString("nextPageToken","");
   if(pageToken.isEmpty())pageToken=null;
  }while(pageToken!=null);
  return result;
 }

 @Override public String ensureFolder(String parentId,String name)throws Exception{
  BackupRules.validateName(name);
  RemoteItem existing=item(parentId,name);
  if(existing!=null){
   if(!existing.folder)throw new IOException("A file blocks the destination folder");
   return existing.id;
  }
  JSONObject body=new JSONObject()
   .put("name",name)
   .put("mimeType",FOLDER_MIME)
   .put("parents",new JSONArray().put(parentId));
  JSONObject created=authorized(token->sendJson("POST",API+"/files?fields="+Uri.encode(FIELDS)+"&supportsAllDrives=true",body,token));
  return created.getString("id");
 }

 @Override public String contentHash(String itemId)throws Exception{
  return authorized(token->streamContent(itemId,token,null));
 }

 @Override public String download(String itemId,File destination)throws Exception{
  try(OutputStream out=new FileOutputStream(destination)){
   return authorized(token->streamContent(itemId,token,out));
  }
 }

 private String streamContent(String itemId,String token,OutputStream out)throws Exception{
  BackupCancellation.check();
  String url=API+"/files/"+Uri.encode(itemId)+"?alt=media&supportsAllDrives=true";
  HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(60000);
   c.setRequestMethod("GET");c.setRequestProperty("Authorization","Bearer "+token);
   int status=c.getResponseCode();
   if(status<200||status>=300)throw new HttpError("Google Drive",status);
   BackupRules.requireFileSize(c.getContentLengthLong());
   try(InputStream in=c.getInputStream()){return BackupRules.hash(in,out);}
  }finally{c.disconnect();}
 }

 @Override public RemoteItem upload(String parentId,String name,File source,String sha256)throws Exception{
  BackupRules.requireFileSize(source.length());
  if(sha256!=null&&source.length()>10L*1024*1024){
   RemoteItem resumed=resumeUpload(parentId,name,source,sha256);
   if(resumed!=null)return resumed;
  }
  JSONObject meta=new JSONObject().put("name",name).put("parents",new JSONArray().put(parentId));
  String initUrl=UPLOAD_API+"/files?uploadType=resumable&fields="+Uri.encode(FIELDS)+"&supportsAllDrives=true";
  String sessionUri=authorized(token->startResumable("POST",initUrl,meta,source.length(),token));
  String key=sha256!=null&&source.length()>10L*1024*1024?checkpointKey(parentId,name):null;
  if(key!=null){
   JSONObject cp=new JSONObject().put("identity",identity(parentId,name,sha256)).put("url",sessionUri);
   checkpoints().edit().putString(key,cp.toString()).commit();
  }
  return sendResumable(sessionUri,source,0,false,key);
 }

 @Override public RemoteItem update(RemoteItem expected,File source)throws Exception{
  BackupRules.requireFileSize(source.length());
  String initUrl=UPLOAD_API+"/files/"+Uri.encode(expected.id)+"?uploadType=resumable&fields="+Uri.encode(FIELDS)+"&supportsAllDrives=true";
  String sessionUri=authorized(token->startResumable("PATCH",initUrl,new JSONObject(),source.length(),token));
  return sendResumable(sessionUri,source,0,false,null);
 }

 @Override public boolean hasUploadCheckpoint(String parentId,String name,String sha256){
  if(pairId==null||sha256==null)return false;
  try{
   JSONObject cp=new JSONObject(checkpoints().getString(checkpointKey(parentId,name),"{}"));
   return identity(parentId,name,sha256).equals(cp.optString("identity"))&&!cp.optString("url").isEmpty();
  }catch(Exception ignored){return false;}
 }

 @Override public RemoteItem resumeUpload(String parentId,String name,File source,String sha256)throws Exception{
  if(pairId==null||sha256==null||source.length()<=10L*1024*1024)return null;
  String key=checkpointKey(parentId,name);
  JSONObject cp=new JSONObject(checkpoints().getString(key,"{}"));
  if(!identity(parentId,name,sha256).equals(cp.optString("identity")))return null;
  String sessionUri=cp.optString("url");
  if(sessionUri.isEmpty())return null;
  long offset;
  try{
   HttpURLConnection c=openSessionConnection(sessionUri,"PUT");
   try{
    c.setRequestProperty("Content-Range","bytes */"+source.length());
    c.setFixedLengthStreamingMode(0);
    c.setDoOutput(true);
    int status=c.getResponseCode();
    if(status==200||status==201){
     JSONObject done=readJson(c);
     checkpoints().edit().remove(key).apply();
     return toRemoteItem(done);
    }
    if(status==308){
     offset=parseRangeNextOffset(c.getHeaderField("Range"));
    }else if(status==404||status==410){
     checkpoints().edit().remove(key).apply();
     return null;
    }else{
     throw new HttpError("Google Drive",status);
    }
   }finally{c.disconnect();}
  }catch(HttpError e){throw e;}
   catch(IOException e){throw new ConnectionInterrupted("Upload connection interrupted",e);}
  if(pairId!=null)Store.history(context,pairId,"Google Drive upload checkpoint: "+offset+" of "+source.length()+" bytes already accepted.");
  return sendResumable(sessionUri,source,offset,offset>0,key);
 }

 @Override public void delete(String itemId,String versionTag)throws Exception{
  JSONObject body=new JSONObject().put("trashed",true);
  authorized(token->sendJson("PATCH",API+"/files/"+Uri.encode(itemId)+"?supportsAllDrives=true",body,token));
 }

 private SharedPreferences checkpoints(){
  return context.getSharedPreferences("gdrive-upload-checkpoints",Context.MODE_PRIVATE);
 }

 private String checkpointKey(String parentId,String name){return (pairId==null?"":pairId)+":"+parentId+"/"+name;}
 private static String identity(String parentId,String name,String sha256){return parentId+"/"+name+"\n"+sha256;}

 private String startResumable(String method,String url,JSONObject body,long totalBytes,String token)throws Exception{
  BackupCancellation.check();
  HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(60000);
   c.setRequestMethod(method);
   c.setRequestProperty("Authorization","Bearer "+token);
   c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
   c.setRequestProperty("X-Upload-Content-Length",String.valueOf(totalBytes));
   byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
   c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);
   try(OutputStream out=c.getOutputStream()){out.write(bytes);}
   int status=c.getResponseCode();
   if(status<200||status>=300)throw new HttpError("Google Drive",status);
   String location=c.getHeaderField("Location");
   if(location==null||location.isEmpty())throw new IOException("Google Drive did not return an upload session URL");
   return location;
  }finally{c.disconnect();}
 }

 private RemoteItem sendResumable(String sessionUri,File file,long offset,boolean resumed,String checkpointKey)throws Exception{
  long length=file.length();
  if(progress!=null)progress.onProgress(offset,length,resumed);
  if(length==0){
   try{
    HttpURLConnection c=openSessionConnection(sessionUri,"PUT");
    try{
     c.setRequestProperty("Content-Type","application/octet-stream");
     c.setDoOutput(true);c.setFixedLengthStreamingMode(0);
     int status=c.getResponseCode();
     if(status!=200&&status!=201)throw new HttpError("Google Drive",status);
     JSONObject done=readJson(c);
     if(checkpointKey!=null)checkpoints().edit().remove(checkpointKey).apply();
     if(progress!=null)progress.onProgress(0,0,resumed);
     return toRemoteItem(done);
    }finally{c.disconnect();}
   }catch(HttpError e){throw e;}
    catch(IOException e){throw new ConnectionInterrupted("Upload connection interrupted",e);}
  }
  try(RandomAccessFile input=new RandomAccessFile(file,"r")){
   byte[] buffer=new byte[CHUNK];
   while(offset<length){
    BackupCancellation.check();
    int count=(int)Math.min(buffer.length,length-offset);
    input.seek(offset);
    input.readFully(buffer,0,count);
    HttpURLConnection c=openSessionConnection(sessionUri,"PUT");
    try{
     c.setRequestProperty("Content-Type","application/octet-stream");
     c.setRequestProperty("Content-Range","bytes "+offset+"-"+(offset+count-1)+"/"+length);
     c.setDoOutput(true);c.setFixedLengthStreamingMode(count);
     try(OutputStream out=c.getOutputStream()){
      for(int pos=0;pos<count;pos+=65536){
       BackupCancellation.check();
       out.write(buffer,pos,Math.min(65536,count-pos));
      }
     }
     int status=c.getResponseCode();
     if(status==200||status==201){
      JSONObject done=readJson(c);
      if(checkpointKey!=null)checkpoints().edit().remove(checkpointKey).apply();
      if(progress!=null)progress.onProgress(length,length,resumed);
      return toRemoteItem(done);
     }
     if(status==308){
      long next=parseRangeNextOffset(c.getHeaderField("Range"));
      if(next<=offset)next=offset+count;
      offset=next;
      if(progress!=null)progress.onProgress(offset,length,resumed);
      continue;
     }
     throw new HttpError("Google Drive",status);
    }catch(HttpError e){throw e;}
     catch(IOException e){throw new ConnectionInterrupted("Upload connection interrupted",e);}
     finally{c.disconnect();}
   }
  }
  throw new IOException("Google Drive upload did not complete");
 }

 private static HttpURLConnection openSessionConnection(String sessionUri,String method)throws Exception{
  BackupCancellation.check();
  URI uri=URI.create(sessionUri);
  if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)
   throw new IOException("Invalid Google Drive upload session address");
  HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
  c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(60000);
  c.setRequestMethod(method);
  return c;
 }

 private static JSONObject sendJson(String method,String url,JSONObject body,String token)throws Exception{
  BackupCancellation.check();
  URI uri=URI.create(url);
  if(!"https".equals(uri.getScheme())||!"www.googleapis.com".equals(uri.getHost()))
   throw new IOException("Untrusted Google Drive address");
  HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(60000);
   c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);
   if(body!=null){
    byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);
    c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
    c.setFixedLengthStreamingMode(data.length);
    try(OutputStream out=c.getOutputStream()){out.write(data);}
   }
   int status=c.getResponseCode();
   if(status<200||status>=300)throw new HttpError("Google Drive",status);
   return readJson(c);
  }finally{c.disconnect();}
 }

 private static JSONObject readJson(HttpURLConnection c)throws Exception{
  try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buf=new byte[8192];int n;
   while((n=in.read(buf))!=-1){
    if(out.size()+n>4*1024*1024)throw new IOException("Response too large");
    out.write(buf,0,n);
   }
   return new JSONObject(out.size()==0?"{}":out.toString(StandardCharsets.UTF_8.name()));
  }
 }

 static RemoteItem toRemoteItem(JSONObject json)throws Exception{
  String mime=json.optString("mimeType");
  boolean folder=FOLDER_MIME.equals(mime);
  boolean workspaceDoc=!folder&&mime.startsWith("application/vnd.google-apps.");
  boolean supported=folder||!workspaceDoc;
  long size=0;
  if(!folder){
   String rawSize=json.optString("size","");
   size=rawSize.isEmpty()?-1:Long.parseLong(rawSize);
  }
  String version=json.has("version")?String.valueOf(json.get("version")):json.optString("modifiedTime");
  JSONArray parents=json.optJSONArray("parents");
  String parentId=parents!=null&&parents.length()>0?parents.optString(0):"";
  return new RemoteItem(
   json.getString("id"),
   json.getString("name"),
   folder,
   size,
   parseModified(json.optString("modifiedTime")),
   version,
   json.optString("md5Checksum"),
   parentId,
   supported,
   supported?null:"Google Docs/Sheets/Slides cannot be synced as files."
  );
 }

 private static long parseModified(String timestamp){
  if(timestamp==null||timestamp.isEmpty())return 0;
  try{return Instant.parse(timestamp).toEpochMilli();}
  catch(java.time.DateTimeException e){return 0;}
 }
}
