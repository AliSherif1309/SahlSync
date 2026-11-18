package com.aly.sahlsync;

import java.io.*;
import java.net.*;

/** Graph content redirects to a short-lived preauthenticated URL. Never forward the token. */
final class RemoteContent {
 interface Connections { HttpURLConnection open(URI uri) throws IOException; }
 static String sha256(String graphAddress,String token)throws Exception{
  return sha256(graphAddress,token,uri->(HttpURLConnection)uri.toURL().openConnection());
 }
 static String sha256(String graphAddress,String token,Connections connections)throws Exception{
  return transfer(graphAddress,token,connections,null);
 }
 static String download(String graphAddress,String token,File destination)throws Exception{
  try(OutputStream out=new FileOutputStream(destination)){return transfer(graphAddress,token,uri->(HttpURLConnection)uri.toURL().openConnection(),out);}
 }
 private static String transfer(String graphAddress,String token,Connections connections,OutputStream output)throws Exception{
  BackupCancellation.check();
  URI address=URI.create(graphAddress);
  if(!"https".equals(address.getScheme())||!"graph.microsoft.com".equals(address.getHost())||address.getUserInfo()!=null)throw new IOException("Untrusted Graph address");
  for(int hop=0;hop<6;hop++){
   if(Thread.currentThread().isInterrupted())throw new InterruptedException();
   if(!"https".equals(address.getScheme())||address.getHost()==null||address.getUserInfo()!=null)throw new IOException("Untrusted content redirect");
   HttpURLConnection connection=connections.open(address);
   try{
    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(30000);connection.setReadTimeout(60000);connection.setRequestMethod("GET");
    if(hop==0)connection.setRequestProperty("Authorization","Bearer "+token);
    int code=connection.getResponseCode();
    if(code==301||code==302||code==303||code==307||code==308){
     String location=connection.getHeaderField("Location");
     if(location==null||location.isEmpty())throw new IOException("Missing content redirect");
     address=address.resolve(location);continue;
    }
    if(code!=200)throw new IOException("Content verification returned HTTP "+code);
    if(connection.getContentLengthLong()>BackupRules.MAX_FILE_BYTES)throw new IOException("Remote file exceeds verification limit");
    try(InputStream in=connection.getInputStream()){return BackupRules.hash(in,output);}
   }finally{connection.disconnect();}
  }
  throw new IOException("Too many content redirects");
 }
}
