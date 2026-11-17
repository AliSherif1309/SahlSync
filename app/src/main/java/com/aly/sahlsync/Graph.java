package com.aly.sahlsync;

import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class Graph {
 static final String ROOT="https://graph.microsoft.com/v1.0";
 private final String token;
 Graph(String token){this.token=token;}
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
  URI uri=URI.create(address);
  if(!"https".equals(uri.getScheme())||!"graph.microsoft.com".equals(uri.getHost()))throw new IOException("Untrusted Graph address");
  HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
  try {
   c.setInstanceFollowRedirects(false); c.setConnectTimeout(30000); c.setReadTimeout(60000);
   c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);
   if(ifMatch!=null)c.setRequestProperty("If-Match",ifMatch);
   if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",type);c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
   int status=c.getResponseCode(); if(status<200||status>=300)throw new HttpError(status);
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>4*1024*1024)throw new IOException("Response too large");}
    return new JSONObject(out.size()==0?"{}":out.toString(StandardCharsets.UTF_8.name()));
   }
  }finally{c.disconnect();}
 }
 String contentHash(String itemId)throws Exception{return RemoteContent.sha256(ROOT+"/me/drive/items/"+Uri.encode(itemId)+"/content",token);}
 JSONObject item(String parent,String name) throws Exception {return json("GET","/me/drive/items/"+Uri.encode(parent)+":/"+Uri.encode(name),null);}
 String folder(String parent,String name) throws Exception {
  BackupRules.validateName(name);
  try{return requireFolder(item(parent,name));}catch(HttpError e){if(e.status!=404)throw e;}
  JSONObject body=new JSONObject().put("name",name).put("folder",new JSONObject()).put("@microsoft.graph.conflictBehavior","fail");
  try{return requireFolder(json("POST","/me/drive/items/"+Uri.encode(parent)+"/children",body));}
  catch(HttpError e){if(e.status!=409)throw e;return requireFolder(item(parent,name));}
 }
 private String requireFolder(JSONObject item)throws Exception{if(!item.has("folder"))throw new IOException("A file blocks the destination folder");return item.getString("id");}
 String download(String itemId,File destination)throws Exception{return RemoteContent.download(ROOT+"/me/drive/items/"+Uri.encode(itemId)+"/content",token,destination);}
 JSONObject upload(String parent,String name,File file) throws Exception {
  return putFile(ROOT+"/me/drive/items/"+Uri.encode(parent)+":/"+Uri.encode(name)+":/content?%40microsoft.graph.conflictBehavior=fail",file,"0");
 }
 JSONObject updateFile(String id,String expectedTag,File file)throws Exception{
  if(expectedTag==null||expectedTag.isEmpty())throw new IOException("Missing file tag");
  return putFile(ROOT+"/me/drive/items/"+Uri.encode(id)+"/content",file,expectedTag);
 }
 private JSONObject putFile(String address,File file,String ifMatch)throws Exception{
  byte[] bytes;
  try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>BackupRules.MAX_FILE_BYTES)throw new IOException("File too large");}bytes=out.toByteArray();
  }
  return request("PUT",address,bytes,"application/octet-stream",ifMatch);
 }
 JSONArray folders(String parent)throws Exception{
  JSONArray all=children(parent),result=new JSONArray();for(int i=0;i<all.length();i++)if(all.getJSONObject(i).has("folder"))result.put(all.get(i));return result;
 }
 JSONArray children(String parent)throws Exception{
  JSONArray result=new JSONArray();String path="/me/drive/items/"+Uri.encode(parent)+"/children?$select=id,name,folder,file,size,createdDateTime&$top=200";
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
