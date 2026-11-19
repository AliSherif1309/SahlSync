package com.aly.sahlsync;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class BackupRulesTest {
 @Test public void retryUsesSameVersionAndChangedContentUsesAnother()throws Exception{
  String first=BackupRules.hash(new ByteArrayInputStream("report".getBytes(StandardCharsets.UTF_8)),null);
  String second=BackupRules.hash(new ByteArrayInputStream("report edited".getBytes(StandardCharsets.UTF_8)),null);
  assertEquals(BackupRules.versionName("report.pdf",first),BackupRules.versionName("report.pdf",first));
  assertNotEquals(BackupRules.versionName("report.pdf",first),BackupRules.versionName("report.pdf",second));
  assertTrue(BackupRules.versionName("report.pdf",first).endsWith(".pdf"));
 }
 @Test public void rejectsTraversalAndRemoteControlCharacters(){
  for(String name:new String[]{"..","a/b","a\\b","report?","a\nfile","x.","x "}){
   try{BackupRules.validateName(name);fail(name);}catch(IllegalArgumentException expected){}
  }
 }
 @Test public void preservesArabicAndSpaces(){BackupRules.validateName("تقرير الصيانة.pdf");}
 @Test public void exclusionsEscapeRegex(){
  assertTrue(BackupRules.excluded("cache/temp.tmp","*.tmp"));
  assertTrue(BackupRules.excluded("private/file.pdf","private/*"));
  assertFalse(BackupRules.excluded("aXpdf","a.pdf"));
  assertFalse(BackupRules.excluded("report.pdf","*.tmp"));
 }
 @Test public void stagesExactBytesAndHash()throws Exception{
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",BackupRules.hash(new ByteArrayInputStream(new byte[0]),out));
  byte[] data={0,1,2,3};BackupRules.hash(new ByteArrayInputStream(data),out);assertArrayEquals(data,out.toByteArray());
 }
 @Test public void largerFilesStayWithinTheOneDriveReplacementLimit()throws Exception{
  BackupRules.requireFileSize(129L*1024*1024);
  BackupRules.requireFileSize(BackupRules.MAX_FILE_BYTES);
  assertTrue(BackupRules.MAX_FILE_BYTES>=2_000_000_000L);
  try{BackupRules.requireFileSize(BackupRules.MAX_FILE_BYTES+1);fail();}
  catch(BackupRules.FileTooLarge expected){}
 }
 @Test public void rejectsOversizedStreamWithoutCompleting()throws Exception{
  InputStream endless=new InputStream(){public int read(){return 0;}public int read(byte[] b){return b.length;}};
  try{BackupRules.hash(endless,null);fail();}catch(BackupRules.FileTooLarge expected){}
 }
}
