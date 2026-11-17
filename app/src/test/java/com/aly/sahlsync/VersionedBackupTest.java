package com.aly.sahlsync;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class VersionedBackupTest {
 private File source;
 private String hash(String value)throws Exception{return BackupRules.hash(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),null);}
 @Before public void setup()throws Exception{source=File.createTempFile("sahl-history-",".txt");write("new");}
 @After public void cleanup(){source.delete();}
 private void write(String value)throws Exception{try(OutputStream out=new FileOutputStream(source)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
 private final class Backend implements VersionedBackup.Backend {
  VersionedBackup.Item remote;String remoteHash;String createdName,conditionalTag;boolean archiveFails,race;int uploads,reads;
  final List<String> operations=new ArrayList<>(),archives=new ArrayList<>();
  Backend(String content)throws Exception{if(content!=null){remote=new VersionedBackup.Item("file-1","tag-1",content.length());remoteHash=VersionedBackupTest.this.hash(content);}}
  public VersionedBackup.Item find(String name){return remote;}
  public String hash(VersionedBackup.Item item){reads++;return remoteHash;}
  public VersionedBackup.Item create(String name,File source)throws Exception{operations.add("create");createdName=name;uploads++;remote=new VersionedBackup.Item("file-1","tag-1",source.length());remoteHash=VersionedBackupTest.this.hash("new");return remote;}
  public void archive(VersionedBackup.Item item,String expected)throws Exception{operations.add("archive");if(archiveFails)throw new IOException("Archive storage unavailable");archives.add(expected);if(race){remote=new VersionedBackup.Item(remote.id,"cloud-edit",3);remoteHash=VersionedBackupTest.this.hash("web");}}
  public VersionedBackup.Item update(VersionedBackup.Item expected,File source)throws Exception{conditionalTag=expected.tag;if(!remote.tag.equals(expected.tag))throw new IOException("Precondition failed");operations.add("update");uploads++;remote=new VersionedBackup.Item(expected.id,"tag-next",source.length());return remote;}
 }
 private VersionedBackup.Receipt receipt(String content)throws Exception{return new VersionedBackup.Receipt("file-1","tag-1",hash(content),content.length());}
 @Test public void createsOneOriginalFilename()throws Exception{
  Backend b=new Backend(null);VersionedBackup.Result r=VersionedBackup.backup(b,"report.txt",source,hash("new"),null);
  assertEquals(VersionedBackup.Outcome.CREATED,r.outcome);assertEquals("report.txt",b.createdName);assertTrue(b.archives.isEmpty());
 }
 @Test public void repeatedUnchangedFileDoesNotCreateVersions()throws Exception{
  Backend b=new Backend("new");VersionedBackup.Result r=VersionedBackup.backup(b,"report.txt",source,hash("new"),receipt("new"));
  assertEquals(VersionedBackup.Outcome.UNCHANGED,r.outcome);assertEquals(0,b.uploads);assertEquals(0,b.reads);assertTrue(b.archives.isEmpty());
 }
 @Test public void changedPhoneFileArchivesBeforeUpdatingSameCloudItem()throws Exception{
  Backend b=new Backend("old");VersionedBackup.Result r=VersionedBackup.backup(b,"report.txt",source,hash("new"),receipt("old"));
  assertEquals(VersionedBackup.Outcome.UPDATED,r.outcome);assertEquals(Arrays.asList("archive","update"),b.operations);assertEquals(hash("old"),b.archives.get(0));assertEquals("file-1",r.receipt.id);assertEquals("tag-1",b.conditionalTag);
 }
 @Test public void metadataChangeChecksBytesAndUsesFreshTagForUpdate()throws Exception{
  Backend b=new Backend("old");b.remote=new VersionedBackup.Item("file-1","thumbnail-tag",3);
  assertEquals(VersionedBackup.Outcome.UPDATED,VersionedBackup.backup(b,"report.txt",source,hash("new"),receipt("old")).outcome);
  assertEquals(1,b.reads);assertEquals("thumbnail-tag",b.conditionalTag);
 }
 @Test public void cloudEditIsNotOverwritten()throws Exception{
  Backend b=new Backend("web");b.remote=new VersionedBackup.Item("file-1","web-tag",3);
  assertEquals(VersionedBackup.Outcome.CONFLICT,VersionedBackup.backup(b,"report.txt",source,hash("new"),receipt("old")).outcome);
  assertEquals(0,b.uploads);assertTrue(b.archives.isEmpty());
 }
 @Test public void unknownDifferentFileRemainsConflict()throws Exception{
  Backend b=new Backend("old");assertEquals(VersionedBackup.Outcome.CONFLICT,VersionedBackup.backup(b,"report.txt",source,hash("new"),null).outcome);assertTrue(b.operations.isEmpty());
 }
 @Test public void unknownIdenticalFileCanBeAdoptedWithoutUpload()throws Exception{
  Backend b=new Backend("new");assertEquals(VersionedBackup.Outcome.UNCHANGED,VersionedBackup.backup(b,"report.txt",source,hash("new"),null).outcome);assertEquals(1,b.reads);assertEquals(0,b.uploads);
 }
 @Test public void failedArchivePreventsOverwrite()throws Exception{
  Backend b=new Backend("old");b.archiveFails=true;
  try{VersionedBackup.backup(b,"report.txt",source,hash("new"),receipt("old"));fail();}catch(IOException expected){}
  assertEquals(Arrays.asList("archive"),b.operations);assertEquals(0,b.uploads);
 }
 @Test public void editDuringArchiveFailsConditionalUpdateAndPreservesSnapshot()throws Exception{
  Backend b=new Backend("old");b.race=true;
  try{VersionedBackup.backup(b,"report.txt",source,hash("new"),receipt("old"));fail();}catch(IOException expected){}
  assertEquals(0,b.uploads);assertEquals(hash("old"),b.archives.get(0));assertEquals(hash("web"),b.remoteHash);
 }
 @Test public void replacementCloudIdentityCannotUseOldReceipt()throws Exception{
  Backend b=new Backend("old");b.remote=new VersionedBackup.Item("different-file","tag-1",3);
  assertEquals(VersionedBackup.Outcome.CONFLICT,VersionedBackup.backup(b,"report.txt",source,hash("new"),receipt("old")).outcome);assertEquals(0,b.uploads);
 }
 @Test public void revertingLocalTextStillArchivesCurrentVersion()throws Exception{
  Backend b=new Backend("new");write("old");
  VersionedBackup.Result r=VersionedBackup.backup(b,"report.txt",source,hash("old"),receipt("new"));
  assertEquals(VersionedBackup.Outcome.UPDATED,r.outcome);assertEquals(hash("new"),b.archives.get(0));
 }
 @Test public void historyAcceptsOnlyRecognizedChecksummedSnapshotNames()throws Exception{
  String content=hash("old"),name="123-00000000-0000-0000-0000-000000000000-"+content+".snapshot";
  assertEquals(content,VersionedBackup.snapshotHash(name));assertNull(VersionedBackup.snapshotHash("origin.json"));assertNull(VersionedBackup.snapshotHash("report.txt"));
 }
}
