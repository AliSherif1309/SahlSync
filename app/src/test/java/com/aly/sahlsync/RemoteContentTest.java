package com.aly.sahlsync;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class RemoteContentTest {
 static final class Reply extends HttpURLConnection {
  final int status;final String location;final byte[] bytes;final Map<String,String> headers=new HashMap<>();boolean disconnected;
  Reply(int status,String location,String data)throws Exception{super(new URL("https://example.com"));this.status=status;this.location=location;this.bytes=data.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
  @Override public void connect(){}
  @Override public void disconnect(){disconnected=true;}
  @Override public boolean usingProxy(){return false;}
  @Override public int getResponseCode(){return status;}
  @Override public String getHeaderField(String key){return "Location".equals(key)?location:null;}
  @Override public long getContentLengthLong(){return bytes.length;}
  @Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
  @Override public void setRequestProperty(String key,String value){headers.put(key,value);}
 }
 @Test public void followsGraphDownloadWithoutForwardingBearerToken()throws Exception{
  Reply graph=new Reply(302,"https://files.1drv.com/download",""),cdn=new Reply(200,null,"photo");List<URI> urls=new ArrayList<>();
  String hash=RemoteContent.sha256("https://graph.microsoft.com/v1.0/me/drive/items/1/content","token",uri->{urls.add(uri);return urls.size()==1?graph:cdn;});
  assertEquals(BackupRules.hash(new ByteArrayInputStream("photo".getBytes()),null),hash);
  assertEquals("Bearer token",graph.headers.get("Authorization"));assertFalse(cdn.headers.containsKey("Authorization"));
  assertTrue(graph.disconnected);assertTrue(cdn.disconnected);assertEquals("files.1drv.com",urls.get(1).getHost());
 }
 @Test public void rejectsHttpDowngradeBeforeOpeningDestination()throws Exception{
  Reply graph=new Reply(302,"http://files.1drv.com/download","");int[] opens={0};
  try{RemoteContent.sha256("https://graph.microsoft.com/content","token",uri->{opens[0]++;return graph;});fail();}catch(IOException expected){}
  assertEquals(1,opens[0]);assertTrue(graph.disconnected);
 }
 @Test public void failureIsNotAValidContentHash()throws Exception{
  Reply failed=new Reply(403,null,"");
  try{RemoteContent.sha256("https://graph.microsoft.com/content","token",uri->failed);fail();}catch(IOException expected){}
  assertTrue(failed.disconnected);
 }
 @Test public void rejectsNonGraphInitialHostWithoutSendingToken()throws Exception{
  try{RemoteContent.sha256("https://example.com/content","token",uri->{throw new AssertionError();});fail();}catch(IOException expected){}
 }
 @Test public void boundsRedirectLoops()throws Exception{
  Reply loop=new Reply(302,"https://graph.microsoft.com/content","");int[] opens={0};
  try{RemoteContent.sha256("https://graph.microsoft.com/content","token",uri->{opens[0]++;return loop;});fail();}catch(IOException expected){}
  assertEquals(6,opens[0]);
 }
}
