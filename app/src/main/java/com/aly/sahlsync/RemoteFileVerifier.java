package com.aly.sahlsync;

/** An ETag covers metadata as well as content; a changed tag alone is not a conflict. */
final class RemoteFileVerifier {
 interface ContentHash { String read() throws Exception; }
 static boolean unchanged(long localSize,String localHash,long remoteSize,String savedETag,String remoteETag,ContentHash remoteHash)throws Exception{
  if(localSize!=remoteSize)return false;
  if(savedETag!=null&&!savedETag.isEmpty()&&savedETag.equals(remoteETag))return true;
  // Recover old/missing receipts only by checking bytes, never by name or size alone.
  return localHash.equals(remoteHash.read());
 }
}
