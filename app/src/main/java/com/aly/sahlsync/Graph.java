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
  URI uri=URI.create(address);
  if(!"https".equals(uri.getScheme())||!"graph.microsoft.com".equals(uri.getHost()))throw new IOException("Untrusted Graph address");
  HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();
  try {
   c.setInstanceFollowRedirects(false); c.setConnectTimeout(30000); c.setReadTimeout(60000);
   c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);
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
 JSONObject upload(String parent,String name,File file) throws Exception {
  byte[] bytes;
  try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>BackupRules.MAX_FILE_BYTES)throw new IOException("File too large");}bytes=out.toByteArray();
  }
  return request("PUT",ROOT+"/me/drive/items/"+Uri.encode(parent)+":/"+Uri.encode(name)+":/content?%40microsoft.graph.conflictBehavior=fail",bytes,"application/octet-stream");
 }
 JSONArray folders(String parent)throws Exception{
  JSONArray result=new JSONArray();String path="/me/drive/items/"+Uri.encode(parent)+"/children?$select=id,name,folder&$top=200";
  int pages=0;
  while(path!=null){
   if(++pages>50)throw new IOException("Too many folders; choose a smaller folder");
   JSONObject page=json("GET",path,null);JSONArray items=page.getJSONArray("value");
   for(int i=0;i<items.length();i++)if(items.getJSONObject(i).has("folder"))result.put(items.getJSONObject(i));
   String next=page.optString("@odata.nextLink","");
   if(!next.isEmpty()&&!next.startsWith(ROOT+"/"))throw new IOException("Untrusted next page");
   path=next.isEmpty()?null:next.substring(ROOT.length());
  }return result;
 }
}
