package com.aly.sahlsync;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
public class BackupCancellationTest {
 @Test public void stoppedWorkerRejectsHashing()throws Exception{
  BackupCancellation.bind(()->true);
  try{BackupRules.hash(new ByteArrayInputStream(new byte[]{1}),null);fail("Must stop");}catch(InterruptedIOException expected){}finally{BackupCancellation.clear();}
 }
 @Test public void clearedScopeAllowsLaterWork()throws Exception{
  BackupCancellation.bind(()->true);BackupCancellation.clear();BackupCancellation.check();
 }
 @Test public void cancellationIsConfinedToWorkerThread()throws Exception{
  BackupCancellation.bind(()->true);
  try{java.util.concurrent.atomic.AtomicReference<Exception> failure=new java.util.concurrent.atomic.AtomicReference<>();Thread other=new Thread(()->{try{BackupCancellation.check();}catch(Exception e){failure.set(e);}});other.start();other.join();assertNull(failure.get());}finally{BackupCancellation.clear();}
 }
}
