package com.aly.sahlsync;

import java.io.IOException;
import java.net.UnknownHostException;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackupRetryTest {
 @Test public void authNetworkErrorsRetryWithoutAskingForSignIn(){
  assertTrue(BackupRetry.transientAuthCode("device_network_not_available"));
  assertTrue(BackupRetry.transientAuthCode("no_network_connection_power_optimization"));
  assertTrue(BackupRetry.transientAuthCode("io_error"));
  assertFalse(BackupRetry.transientAuthCode("interaction_required"));
  assertFalse(BackupRetry.transientAuthCode(null));
 }
 @Test public void wrappedNetworkErrorsRetryButUnrelatedIoDoesNot(){
  assertTrue(BackupRetry.connectionFailure(new IOException(new UnknownHostException())));
  assertFalse(BackupRetry.connectionFailure(new IOException("Disk full")));
 }
}
