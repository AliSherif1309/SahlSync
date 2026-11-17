package com.aly.sahlsync;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RemoteFileVerifierTest {
 private String hash(String text)throws Exception{return BackupRules.hash(new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),null);}
 @Test public void metadataChangedButBytesMatchIsUnchanged()throws Exception{
  String content=hash("photo");
  assertTrue(RemoteFileVerifier.unchanged(5,content,5,"upload-etag","thumbnail-etag",()->content));
 }
 @Test public void sameNameAndSizeWithDifferentBytesIsConflict()throws Exception{
  assertFalse(RemoteFileVerifier.unchanged(5,hash("photo"),5,"old","new",()->hash("other")));
 }
 @Test public void oldOrMissingReceiptIsRecoveredOnlyByContentVerification()throws Exception{
  String content=hash("photo");AtomicInteger reads=new AtomicInteger();
  assertTrue(RemoteFileVerifier.unchanged(5,content,5,"","remote",()->{reads.incrementAndGet();return content;}));
  assertEquals(1,reads.get());
 }
 @Test public void matchingReceiptAvoidsRedownload()throws Exception{
  assertTrue(RemoteFileVerifier.unchanged(5,hash("photo"),5,"same","same",()->{throw new AssertionError("Unnecessary download");}));
 }
 @Test public void differentSizeIsConflictWithoutDownloading()throws Exception{
  assertFalse(RemoteFileVerifier.unchanged(5,hash("photo"),6,"same","same",()->{throw new AssertionError();}));
 }
 @Test public void failedVerificationIsNotClassifiedAsUnchanged()throws Exception{
  try{RemoteFileVerifier.unchanged(5,hash("photo"),5,"old","new",()->{throw new IOException("offline");});fail();}catch(IOException expected){}
 }
}
