package com.aly.sahlsync;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;
/** Scoped to the worker thread; UI history downloads have no worker cancellation scope. */
final class BackupCancellation {
 private static final ThreadLocal<BooleanSupplier> signal=new ThreadLocal<>();
 static void bind(BooleanSupplier stopped){signal.set(stopped);}
 static void clear(){signal.remove();}
 static void check()throws InterruptedIOException{
  BooleanSupplier stopped=signal.get();
  if(Thread.currentThread().isInterrupted()||(stopped!=null&&stopped.getAsBoolean()))throw new InterruptedIOException("Backup stopped");
 }
}
