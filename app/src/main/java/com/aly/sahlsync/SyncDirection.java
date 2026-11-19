package com.aly.sahlsync;

enum SyncDirection {
 UPLOAD, DOWNLOAD, NEWEST;

 static SyncDirection from(String stored){
  if("download".equals(stored))return DOWNLOAD;
  if("newest".equals(stored))return NEWEST;
  return UPLOAD;
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
