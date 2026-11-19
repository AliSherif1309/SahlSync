package com.aly.sahlsync;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

final class LanPeer {
 private static final String HEX="[0-9a-f]{64}";
 private final String host,address,token;
 private final SSLSocketFactory sockets;
 private File partial,ticketFile;
 final String identity;

 LanPeer(String code)throws GeneralSecurityException {
  String[] fields=code.trim().split("\\s+");
  if(fields.length!=4||!fields[0].equals("SAHL1")||!fields[2].matches(HEX)||!fields[3].matches(HEX))
   throw new IllegalArgumentException("Check the pairing code on your PC.");
  String[] endpoint=fields[1].split(":",-1);
  if(endpoint.length!=2||!endpoint[1].matches("[0-9]{1,5}"))throw new IllegalArgumentException("Invalid PC address.");
  String[] octets=endpoint[0].split("\\.",-1);
  if(octets.length!=4)throw new IllegalArgumentException("Use the PC's local IPv4 address.");
  for(String part:octets)if(!part.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(part)>255)
   throw new IllegalArgumentException("Invalid PC address.");
  int a=Integer.parseInt(octets[0]),b=Integer.parseInt(octets[1]),port=Integer.parseInt(endpoint[1]);
  // Debug builds can exercise the same protocol using an ADB reverse port.
  boolean localTest=BuildConfig.DEBUG&&endpoint[0].equals("127.0.0.1");
  if(port<1||port>65535||!(localTest||a==10||a==172&&b>=16&&b<=31||a==192&&b==168))
   throw new IllegalArgumentException("Use a private Wi-Fi address and a valid port.");
  host=endpoint[0];
  address="https://"+host+":"+port;
  token=fields[3];
  identity=fields[2];
  SSLContext tls=SSLContext.getInstance("TLS");
  tls.init(null,new TrustManager[]{new X509TrustManager(){
   @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
   @Override public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{
    throw new CertificateException("Client certificates are not used.");
   }
   @Override public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{
    if(chain==null||chain.length==0)throw new CertificateException("Missing PC certificate.");
    chain[0].checkValidity();
    try{
     byte[] hash=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
     byte[] expected=new byte[32];
     for(int i=0;i<expected.length;i++)expected[i]=(byte)Integer.parseInt(identity.substring(i*2,i*2+2),16);
     if(!MessageDigest.isEqual(hash,expected))throw new CertificateException("PC certificate changed.");
    }catch(NoSuchAlgorithmException error){throw new CertificateException(error);}
   }
  }},null);
  sockets=tls.getSocketFactory();
 }

 private HttpsURLConnection connect(String route,String method)throws IOException{
  HttpsURLConnection connection=(HttpsURLConnection)new URL(address+route).openConnection(Proxy.NO_PROXY);
  connection.setSSLSocketFactory(sockets);
  // The certificate is authenticated by its pairing fingerprint.
  connection.setHostnameVerifier((name,session)->host.equals(name));
  connection.setInstanceFollowRedirects(false);
  connection.setConnectTimeout(10000);
  connection.setReadTimeout(30000);
  connection.setRequestMethod(method);
  connection.setRequestProperty("Authorization","Bearer "+token);
  return connection;
 }

 void verifyPairing()throws IOException{
  HttpsURLConnection connection=connect("/v1/ping","GET");
  try{check(connection);}finally{connection.disconnect();}
 }

 JSONObject inventory()throws Exception{
  HttpsURLConnection connection=connect("/v1/manifest","GET");
  try{
   check(connection);
   try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
    byte[] bytes=new byte[262144];
    int count;
    while((count=input.read(bytes))!=-1){
     BackupCancellation.check();
     if(output.size()+count>16*1024*1024)throw new IOException("PC inventory is too large.");
     output.write(bytes,0,count);
    }
    return new JSONObject(output.toString("UTF-8"));
   }
  }finally{connection.disconnect();}
 }

 void enableResume(File cache,String key){
  String prefix="lan-resume-"+java.util.UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
  partial=new File(cache,prefix+".part");
  ticketFile=new File(cache,prefix+".txt");
 }

 void upload(String name,File source,String hash,String expected)throws Exception{
  long length=source.length();
  long offset=partial==null?0:uploadOffset(name,hash,expected,length);
  HttpsURLConnection connection=connect("/v1/file"+pathQuery(name),"PUT");
  try{
   uploadHeaders(connection,hash,expected,length);
   if(partial!=null)connection.setRequestProperty("X-Sahl-Offset",Long.toString(offset));
   connection.setDoOutput(true);
   connection.setFixedLengthStreamingMode(length-offset);
   try(RandomAccessFile input=new RandomAccessFile(source,"r");OutputStream output=connection.getOutputStream()){
    input.seek(offset);
    byte[] bytes=new byte[262144];
    int count;
    while((count=input.read(bytes))!=-1){BackupCancellation.check();output.write(bytes,0,count);}
   }
   check(connection);
  }finally{connection.disconnect();}
 }

 private long uploadOffset(String name,String hash,String expected,long length)throws Exception{
  HttpsURLConnection connection=connect("/v1/upload-offset"+pathQuery(name),"GET");
  try{
   uploadHeaders(connection,hash,expected,length);
   check(connection);
   try(InputStream input=connection.getInputStream()){
    byte[] body=new byte[128];
    int size=0,count;
    while(size<body.length&&(count=input.read(body,size,body.length-size))!=-1)size+=count;
    if(size==body.length)throw new IOException("Invalid upload checkpoint.");
    long offset=new JSONObject(new String(body,0,size,StandardCharsets.UTF_8)).getLong("offset");
    if(offset<0||offset>length)throw new IOException("Invalid upload checkpoint.");
    return offset;
   }
  }finally{connection.disconnect();}
 }

 private void uploadHeaders(HttpsURLConnection connection,String hash,String expected,long length){
  connection.setRequestProperty("If-Match",expected);
  connection.setRequestProperty("X-Sahl-SHA256",hash);
  if(partial!=null)connection.setRequestProperty("X-Sahl-Total",Long.toString(length));
 }

 void download(String name,File destination,String expected,long size)throws Exception{
  BackupRules.requireFileSize(size);
  if(partial==null){
   HttpsURLConnection connection=connect("/v1/file"+pathQuery(name),"GET");
   try{
    connection.setRequestProperty("If-Match",expected);
    check(connection);
    try(InputStream input=connection.getInputStream();OutputStream output=new FileOutputStream(destination)){
     if(!expected.equals(BackupRules.hash(input,output))||destination.length()!=size)throw new Changed();
    }
   }finally{connection.disconnect();}
   return;
  }
  String ticket=name+"\n"+expected+"\n"+size;
  boolean matches=ticketFile.isFile()&&new String(java.nio.file.Files.readAllBytes(ticketFile.toPath()),StandardCharsets.UTF_8).equals(ticket);
  if(!matches||partial.length()>size){
   java.nio.file.Files.deleteIfExists(partial.toPath());
   java.nio.file.Files.write(ticketFile.toPath(),ticket.getBytes(StandardCharsets.UTF_8));
  }
  long offset=partial.length();
  try{
   if(offset<size||!partial.isFile())receive(name,expected,size,offset);
   try(InputStream input=new FileInputStream(partial);OutputStream output=new FileOutputStream(destination)){
    if(!expected.equals(BackupRules.hash(input,output))||destination.length()!=size)throw new Changed();
   }
  }catch(Changed error){
   clearDownload();
   throw error;
  }
  clearDownload();
 }

 private void clearDownload()throws IOException{
  java.nio.file.Files.deleteIfExists(partial.toPath());
  java.nio.file.Files.deleteIfExists(ticketFile.toPath());
 }

 private void receive(String name,String expected,long size,long offset)throws Exception{
  HttpsURLConnection connection=connect("/v1/file"+pathQuery(name),"GET");
  try{
   connection.setRequestProperty("If-Match",expected);
   if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
   int status=connection.getResponseCode();
   boolean append=offset>0&&status==206;
   if(append){
    String range="bytes "+offset+"-"+(size-1)+"/"+size;
    if(!range.equals(connection.getHeaderField("Content-Range")))throw new Changed();
   }else check(connection);
   long remaining=size-(append?offset:0);
   try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(partial,append)){
    byte[] block=new byte[262144];
    int count;
    while((count=in.read(block))!=-1){
     BackupCancellation.check();
     if(count>remaining)throw new Changed();
     out.write(block,0,count);
     remaining-=count;
    }
   }
   if(remaining!=0)throw new IOException("Download interrupted. Retry to continue.");
  }finally{connection.disconnect();}
 }

 private static String pathQuery(String name)throws UnsupportedEncodingException{
  return "?path="+URLEncoder.encode(name,StandardCharsets.UTF_8.name());
 }

 /** Deletes a PC file only if it still has the expected contents. The PC keeps it in a recovery folder. */
 void delete(String name,String expected)throws Exception{
  HttpsURLConnection connection=connect("/v1/file"+pathQuery(name),"DELETE");
  try{connection.setRequestProperty("If-Match",expected);check(connection);}finally{connection.disconnect();}
 }

 /**
  * Finds the paired PC on the current Wi-Fi when its address changed. The reply is only trusted for the
  * address: every connection is still checked against the pinned certificate fingerprint.
  */
 static String discover(String code){
  String[] fields=code.trim().split("\\s+");
  if(fields.length!=4)return null;
  String port=fields[1].substring(fields[1].indexOf(':')+1);
  try(DatagramSocket socket=new DatagramSocket()){
   socket.setBroadcast(true);
   socket.setSoTimeout(1500);
   byte[] ask=("SAHL-FIND "+fields[2]).getBytes(StandardCharsets.US_ASCII);
   for(int attempt=0;attempt<3;attempt++){
    socket.send(new DatagramPacket(ask,ask.length,InetAddress.getByName("255.255.255.255"),Integer.parseInt(port)));
    try{
     byte[] buffer=new byte[64];
     DatagramPacket reply=new DatagramPacket(buffer,buffer.length);
     socket.receive(reply);
     String text=new String(reply.getData(),0,reply.getLength(),StandardCharsets.US_ASCII).trim();
     if(!text.matches("SAHL-HERE [0-9]{1,5}"))continue;
     String found="SAHL1 "+reply.getAddress().getHostAddress()+":"+text.substring(10)+" "+fields[2]+" "+fields[3];
     new LanPeer(found);
     return found;
    }catch(SocketTimeoutException ignored){/* Ask again. */}
   }
  }catch(Exception ignored){/* Not found on this network. */}
  return null;
 }

 static final class Changed extends IOException {}

 private static void check(HttpURLConnection connection)throws IOException{
  int status=connection.getResponseCode();
  if(status==404||status==409||status==412)throw new Changed();
  if(status==401)throw new IOException("Pair again using the code on your PC.");
  if(status!=200)throw new IOException("The PC could not complete the request (HTTP "+status+").");
 }
}
