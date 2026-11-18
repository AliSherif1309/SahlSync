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
}
