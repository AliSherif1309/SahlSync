package com.aly.sahlsync;

enum SyncDirection {
 UPLOAD, DOWNLOAD, NEWEST;

 static SyncDirection from(String stored){
  if("download".equals(stored))return DOWNLOAD;
  if("newest".equals(stored))return NEWEST;
  return UPLOAD;
 }

  /** Two-way only: decides whether a copy missing on one side was deleted there since the last sync. */
 enum Deletion { NONE, DELETE_CLOUD, DELETE_PHONE }
 Deletion deletion(boolean phone,boolean cloud,boolean synced,boolean phoneUnchanged,boolean cloudUnchanged){
  if(this!=NEWEST||!synced||phone==cloud)return Deletion.NONE;
  // An edit always wins over a delete: changed copies are restored instead of removed.
  if(!phone&&cloudUnchanged)return Deletion.DELETE_CLOUD;
  if(!cloud&&phoneUnchanged)return Deletion.DELETE_PHONE;
  return Deletion.NONE;
 }

 enum Action { UPLOAD, DOWNLOAD, UNCHANGED, SKIP, CONFLICT }

 Action choose(boolean phone,boolean cloud,boolean same,long phoneModified,long cloudModified){
  if(!phone)return Action.DOWNLOAD;
  if(!cloud)return this==DOWNLOAD?Action.SKIP:Action.UPLOAD;
  if(same)return Action.UNCHANGED;
  if(phoneModified<=0||cloudModified<=0||phoneModified==cloudModified)return Action.CONFLICT;
  if(cloudModified>phoneModified)return Action.DOWNLOAD;
  return this==DOWNLOAD?Action.CONFLICT:Action.UPLOAD;
 }
}
