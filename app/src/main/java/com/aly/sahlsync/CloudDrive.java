package com.aly.sahlsync;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Provider-independent cloud storage contract shared by OneDrive, Google Drive, and future backends. */
interface CloudDrive {
 final class RemoteItem {
  final String id,name,versionTag,fingerprint,parentId,unsupportedReason;
  final boolean folder,supported;
  final long size,modifiedMillis;

  RemoteItem(String id,String name,boolean folder,long size,long modifiedMillis,
   String versionTag,String fingerprint,String parentId,boolean supported,String unsupportedReason){
   this.id=id;
   this.name=name;
   this.folder=folder;
   this.size=size;
   this.modifiedMillis=modifiedMillis;
   this.versionTag=versionTag==null?"":versionTag;
   this.fingerprint=fingerprint==null?"":fingerprint;
   this.parentId=parentId==null?"":parentId;
   this.supported=supported;
   this.unsupportedReason=unsupportedReason;
  }

  VersionedBackup.Item toBackupItem(){return new VersionedBackup.Item(id,versionTag,size);}
 }

 final class LocalDigest {
  final String sha256,quickXor,md5;
  final long size;
  LocalDigest(String sha256,String quickXor,String md5,long size){
   this.sha256=sha256;
   this.quickXor=quickXor;
   this.md5=md5;
   this.size=size;
  }
 }

 final class AuthRequired extends IOException {
  AuthRequired(String message){super(message);}
  AuthRequired(String message,Throwable cause){super(message,cause);}
 }

 final class ConnectionInterrupted extends IOException {
  ConnectionInterrupted(String message,Throwable cause){super(message,cause);}
 }

 final class HttpError extends IOException {
  final int status;
  final boolean retryable,conflict;
  HttpError(String provider,int status){
   super(provider+" returned HTTP "+status);
   this.status=status;
   this.retryable=status==429||status>=500;
   this.conflict=status==409||status==412;
  }
 }

 String providerName();
 RemoteItem item(String parentId,String name)throws Exception;
 List<RemoteItem> children(String parentId)throws Exception;
 String ensureFolder(String parentId,String name)throws Exception;
 String contentHash(String itemId)throws Exception;
 String download(String itemId,File destination)throws Exception;
 RemoteItem upload(String parentId,String name,File source,String sha256)throws Exception;
 RemoteItem update(RemoteItem expected,File source)throws Exception;
 boolean hasUploadCheckpoint(String parentId,String name,String sha256);
 RemoteItem resumeUpload(String parentId,String name,File source,String sha256)throws Exception;
 void delete(String itemId,String versionTag)throws Exception;
 boolean wantsQuickXor();
 boolean wantsMd5();
 boolean matchesFingerprint(RemoteItem item,LocalDigest local);
}
