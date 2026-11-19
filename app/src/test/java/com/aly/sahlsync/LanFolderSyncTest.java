package com.aly.sahlsync;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.aly.sahlsync.LanFolderSync.Action.*;

public class LanFolderSyncTest {
 @Test public void copiesNewFilesAndDoesNotPropagateDeletion(){
  assertEquals(SEND,LanFolderSync.action("a","",""));
  assertEquals(RECEIVE,LanFolderSync.action("","a",""));
  assertEquals(SEND,LanFolderSync.action("a","","a"));
  assertEquals(RECEIVE,LanFolderSync.action("","a","a"));
 }

 @Test public void transfersOnlyTheChangedSide(){
  assertEquals(SEND,LanFolderSync.action("b","a","a"));
  assertEquals(RECEIVE,LanFolderSync.action("a","b","a"));
  assertEquals(UNCHANGED,LanFolderSync.action("b","b","a"));
 }

 @Test public void preservesBothOriginalsWhenChangesDisagree(){
  assertEquals(CONFLICT,LanFolderSync.action("a","b",""));
  assertEquals(CONFLICT,LanFolderSync.action("b","c","a"));
 }
}
