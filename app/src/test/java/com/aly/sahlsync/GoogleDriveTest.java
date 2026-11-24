package com.aly.sahlsync;

import org.junit.Test;
import static org.junit.Assert.*;

public class GoogleDriveTest {
 @Test public void escapesSingleQuotesAndBackslashesInDriveQueries(){
  assertEquals("My Folder",GoogleDriveDrive.escapeQuery("My Folder"));
  assertEquals("Ali\\'s Photos",GoogleDriveDrive.escapeQuery("Ali's Photos"));
  assertEquals("a\\\\b\\'c",GoogleDriveDrive.escapeQuery("a\\b'c"));
 }

 @Test public void parsesResumableRangeHeaders(){
  assertEquals(0L,GoogleDriveDrive.parseRangeNextOffset(null));
  assertEquals(0L,GoogleDriveDrive.parseRangeNextOffset(""));
  assertEquals(1L,GoogleDriveDrive.parseRangeNextOffset("bytes=0-0"));
  assertEquals(5242880L,GoogleDriveDrive.parseRangeNextOffset("bytes=0-5242879"));
  assertEquals(0L,GoogleDriveDrive.parseRangeNextOffset("invalid"));
 }
}
