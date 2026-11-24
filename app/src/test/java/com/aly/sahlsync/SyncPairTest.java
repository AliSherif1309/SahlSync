package com.aly.sahlsync;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncPairTest {
 @Test public void defaultsToOneDriveWhenProviderIsMissing()throws Exception{
  JSONObject raw=new JSONObject()
   .put("id","pair-1")
   .put("name","Photos")
   .put("local","content://tree/1")
   .put("remote","drive-1")
   .put("remoteName","Camera Roll")
   .put("account","acc-1")
   .put("direction","newest");
  SyncPair pair=SyncPair.fromJson(raw);
  assertEquals(SyncProvider.ONEDRIVE,pair.provider);
  assertEquals(SyncDirection.NEWEST,pair.direction);
  assertTrue(pair.unmetered);
  assertTrue(pair.syncDeletions);
  assertEquals(10,pair.keepVersions);
  JSONObject roundTrip=pair.toJson();
  assertEquals("onedrive",roundTrip.getString("provider"));
  assertEquals("newest",roundTrip.getString("direction"));
 }

 @Test public void preservesGoogleDriveProvider()throws Exception{
  SyncPair pair=new SyncPair("pair-2");
  pair.name="Docs";
  pair.provider=SyncProvider.GOOGLE_DRIVE;
  pair.direction=SyncDirection.DOWNLOAD;
  pair.unmetered=false;
  pair.charging=true;
  pair.keepVersions=30;
  SyncPair decoded=SyncPair.fromJson(pair.toJson());
  assertEquals(SyncProvider.GOOGLE_DRIVE,decoded.provider);
  assertEquals(SyncDirection.DOWNLOAD,decoded.direction);
  assertFalse(decoded.unmetered);
  assertTrue(decoded.charging);
  assertEquals(30,decoded.keepVersions);
 }
}
