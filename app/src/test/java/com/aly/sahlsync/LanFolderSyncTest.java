package com.aly.sahlsync;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.aly.sahlsync.LanFolderSync.Action.*;
import static com.aly.sahlsync.LanFolderSync.Mode.*;

public class LanFolderSyncTest {
 @Test public void copiesMissingFilesOnlyInTheSelectedDirection(){
  assertEquals(SEND,LanFolderSync.action(TWO_WAY,"a","",0,0));
  assertEquals(RECEIVE,LanFolderSync.action(TWO_WAY,"","a",0,0));
  assertEquals(SKIP,LanFolderSync.action(SEND_ONLY,"","a",0,0));
  assertEquals(SKIP,LanFolderSync.action(RECEIVE_ONLY,"a","",0,0));
 }

 @Test public void transfersNewestAndRespectsOneWayModes(){
  assertEquals(SEND,LanFolderSync.action(TWO_WAY,"a","b",2,1));
  assertEquals(RECEIVE,LanFolderSync.action(TWO_WAY,"a","b",1,2));
  assertEquals(SEND,LanFolderSync.action(SEND_ONLY,"a","b",2,1));
  assertEquals(RECEIVE,LanFolderSync.action(RECEIVE_ONLY,"a","b",1,2));
  assertEquals(SKIP,LanFolderSync.action(RECEIVE_ONLY,"a","b",2,1));
  assertEquals(SKIP,LanFolderSync.action(SEND_ONLY,"a","b",1,2));
  assertEquals(UNCHANGED,LanFolderSync.action(TWO_WAY,"a","a",1,2));
 }

 @Test public void preservesBothCopiesIfTimeCannotDecide(){
  assertEquals(CONFLICT,LanFolderSync.action(TWO_WAY,"a","b",1,1));
  assertEquals(CONFLICT,LanFolderSync.action(TWO_WAY,"a","b",0,2));
 }
}
