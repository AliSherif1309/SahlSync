package com.aly.sahlsync;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import org.json.*;

final class ReleaseUpdates {
 private static final String REPO="AliSherif1309/SahlSync";
 private static final String RELEASES="https://github.com/"+REPO+"/releases";
 private static final Pattern APK=Pattern.compile("SahlSync-v([0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?)-([0-9]{1,10})\\.apk");

 static final class Release {
  final String version,page,apkUrl,notes;
  final long code;
  Release(String version,String page,long code,String apkUrl,String notes){this.version=version;this.page=page;this.code=code;this.apkUrl=apkUrl;this.notes=notes;}
 }

 static Release latest()throws Exception{
  URL endpoint=new URL("https://api.github.com/repos/"+REPO+"/releases?per_page=20");
  HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection();
  try{
   connection.setInstanceFollowRedirects(false);
   connection.setConnectTimeout(10000);
   connection.setReadTimeout(10000);
   connection.setRequestProperty("Accept","application/vnd.github+json");
   if(connection.getResponseCode()!=200)throw new IOException("GitHub release check failed");
   try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
    byte[] buffer=new byte[8192];
    int count;
    while((count=input.read(buffer))!=-1){
     if(output.size()+count>1024*1024)throw new IOException("Unexpected release response size");
     output.write(buffer,0,count);
    }
    return select(new JSONArray(new String(output.toByteArray(),StandardCharsets.UTF_8)));
   }
  }finally{connection.disconnect();}
 }

 /** First non-blank line of the release notes, like Sahl Adhan shows. */
 private static String firstLine(String body){
  for(String line:body.split("\n")){String t=line.trim();if(!t.isEmpty()&&!t.startsWith("#"))return t;}
  return "";
 }

 private static Release select(JSONArray releases)throws JSONException{
  Release best=null;
  for(int index=0;index<releases.length();index++){
   JSONObject release=releases.getJSONObject(index);
   if(release.optBoolean("draft"))continue;
   JSONArray assets=release.optJSONArray("assets");
   if(assets==null)continue;
   for(int assetIndex=0;assetIndex<assets.length();assetIndex++){
    JSONObject asset=assets.getJSONObject(assetIndex);
    Matcher match=APK.matcher(asset.optString("name"));
    if(!match.matches()||!"uploaded".equals(asset.optString("state")))continue;
    String version=match.group(1),tag="v"+version;
    if(!tag.equals(release.optString("tag_name")))continue;
    long code=Long.parseLong(match.group(2));
    if(code<=0||code>Integer.MAX_VALUE)continue;
    String url=asset.optString("browser_download_url");
    if(!url.startsWith("https://github.com/"+REPO+"/releases/download/"))continue;
    if(best==null||code>best.code)best=new Release(version,RELEASES+"/tag/"+tag,code,url,firstLine(release.optString("body")));
   }
  }
  return best;
 }
}
