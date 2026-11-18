package com.aly.sahlsync;

import java.net.*;

final class BackupRetry {
 static final int MAX_RETRIES=3;

 static boolean transientAuthCode(String code){
  return "device_network_not_available".equals(code)
   ||"no_network_connection".equals(code)
   ||"no_network_connection_power_optimization".equals(code)
   ||"io_error".equals(code)
   ||"temporarily_unavailable".equals(code)
   ||"server_error".equals(code);
 }

 static boolean connectionFailure(Throwable error){
  for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause()){
   if(error instanceof UnknownHostException||error instanceof SocketTimeoutException
    ||error instanceof SocketException)return true;
  }
  return false;
 }
}
