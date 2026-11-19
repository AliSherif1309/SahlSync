package com.aly.sahlsync;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class SyncDirectionTest {
 @Test public void newestChoosesEitherDirection(){
  assertEquals(SyncDirection.Action.DOWNLOAD,SyncDirection.NEWEST.choose(true,true,false,10,20));
  assertEquals(SyncDirection.Action.UPLOAD,SyncDirection.NEWEST.choose(true,true,false,20,10));
 }
 @Test public void tiesAndMissingDatesPreserveDifferentCopies(){
  assertEquals(SyncDirection.Action.CONFLICT,SyncDirection.NEWEST.choose(true,true,false,10,10));
  assertEquals(SyncDirection.Action.CONFLICT,SyncDirection.NEWEST.choose(true,true,false,0,10));
  assertEquals(SyncDirection.Action.UNCHANGED,SyncDirection.NEWEST.choose(true,true,true,0,0));
 }
 @Test public void missingCopyIsRestoredInTheAllowedDirection(){
  assertEquals(SyncDirection.Action.DOWNLOAD,SyncDirection.NEWEST.choose(false,true,false,0,0));
  assertEquals(SyncDirection.Action.UPLOAD,SyncDirection.NEWEST.choose(true,false,false,0,0));
  assertEquals(SyncDirection.Action.SKIP,SyncDirection.DOWNLOAD.choose(true,false,false,0,0));
  assertEquals(SyncDirection.Action.CONFLICT,SyncDirection.DOWNLOAD.choose(true,true,false,20,10));
 }
 @Test public void deletionsFollowTheLastSync(){
  assertEquals(SyncDirection.Deletion.DELETE_CLOUD,SyncDirection.NEWEST.deletion(false,true,true,false,true));
  assertEquals(SyncDirection.Deletion.DELETE_PHONE,SyncDirection.NEWEST.deletion(true,false,true,true,false));
 }
 @Test public void editsWinOverDeletes(){
  assertEquals(SyncDirection.Deletion.NONE,SyncDirection.NEWEST.deletion(false,true,true,false,false));
  assertEquals(SyncDirection.Deletion.NONE,SyncDirection.NEWEST.deletion(true,false,true,false,false));
 }
 @Test public void neverSyncedOrOneWayNeverDeletes(){
  assertEquals(SyncDirection.Deletion.NONE,SyncDirection.NEWEST.deletion(false,true,false,false,true));
  assertEquals(SyncDirection.Deletion.NONE,SyncDirection.UPLOAD.deletion(false,true,true,false,true));
  assertEquals(SyncDirection.Deletion.NONE,SyncDirection.DOWNLOAD.deletion(true,false,true,true,false));
 }
}
