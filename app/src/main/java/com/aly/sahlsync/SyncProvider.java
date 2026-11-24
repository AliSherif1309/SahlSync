package com.aly.sahlsync;

enum SyncProvider {
 ONEDRIVE("onedrive","OneDrive"),
 GOOGLE_DRIVE("gdrive","Google Drive");

 final String id,displayName;

 SyncProvider(String id,String displayName){
  this.id=id;
  this.displayName=displayName;
 }

 static SyncProvider from(String stored){
  if("gdrive".equals(stored))return GOOGLE_DRIVE;
  return ONEDRIVE;
 }
}
