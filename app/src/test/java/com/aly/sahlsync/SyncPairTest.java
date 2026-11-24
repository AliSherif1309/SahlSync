package com.aly.sahlsync;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncPairTest {
 @Test public void syncProviderDefaultsToOneDrive(){
  assertEquals(SyncProvider.ONEDRIVE,SyncProvider.from(null));
  assertEquals(SyncProvider.ONEDRIVE,SyncProvider.from(""));
  assertEquals(SyncProvider.ONEDRIVE,SyncProvider.from("onedrive"));
  assertEquals(SyncProvider.ONEDRIVE,SyncProvider.from("unknown"));
  assertEquals(SyncProvider.GOOGLE_DRIVE,SyncProvider.from("gdrive"));
 }

 @Test public void syncPairHasSafeDefaults(){
  SyncPair pair=new SyncPair("pair-1");
  assertEquals("pair-1",pair.id);
  assertEquals(SyncProvider.ONEDRIVE,pair.provider);
  assertEquals(SyncDirection.UPLOAD,pair.direction);
  assertTrue(pair.unmetered);
  assertFalse(pair.charging);
  assertTrue(pair.syncDeletions);
  assertTrue(pair.notify);
  assertEquals(10,pair.keepVersions);
 }

 @Test public void cloudDriveHttpErrorClassifiesRetryAndConflict(){
  CloudDrive.HttpError rateLimit=new CloudDrive.HttpError("OneDrive",429);
  assertTrue(rateLimit.retryable);
  assertFalse(rateLimit.conflict);

  CloudDrive.HttpError serverError=new CloudDrive.HttpError("Google Drive",503);
  assertTrue(serverError.retryable);
  assertFalse(serverError.conflict);

  CloudDrive.HttpError conflict=new CloudDrive.HttpError("OneDrive",412);
  assertFalse(conflict.retryable);
  assertTrue(conflict.conflict);
 }
}
