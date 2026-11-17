package com.aly.sahlsync;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Content addressing makes retries idempotent and keeps changed versions separate. */
public final class BackupRules {
 public static final long MAX_FILE_BYTES=20L*1024*1024;
 public static void validateName(String name){
  if(name==null || name.isBlank() || name.equals(".") || name.equals("..") ||
    name.length()>200 || name.matches(".*[\\x00-\\x1f\\\\/:*?\"<>|].*") ||
    name.endsWith(".") || name.endsWith(" ")) throw new IllegalArgumentException("Unsupported file name");
 }
 public static String hash(InputStream input, OutputStream copy) throws Exception {
  MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[65536]; int n; long size=0;
  while((n=input.read(b))!=-1){size+=n; if(size>MAX_FILE_BYTES)throw new IOException("File exceeds 20 MiB alpha limit"); digest.update(b,0,n); if(copy!=null)copy.write(b,0,n);}
  StringBuilder out=new StringBuilder(); for(byte v:digest.digest())out.append(String.format(Locale.ROOT,"%02x",v&255)); return out.toString();
 }
 public static String versionName(String name,String hash){
  validateName(name); if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid content hash");
  int dot=name.lastIndexOf('.');
  return dot>0 ? name.substring(0,dot)+"--"+hash+name.substring(dot) : name+"--"+hash;
 }
 public static boolean excluded(String relative,String patterns){
  for(String line:patterns.split("\\n")){
   String pattern=line.trim(); if(pattern.isEmpty())continue;
   String regex="\\Q"+pattern.replace("*","\\E.*\\Q").replace("?","\\E.\\Q")+"\\E";
   if(relative.matches(regex))return true;
  } return false;
 }
}
