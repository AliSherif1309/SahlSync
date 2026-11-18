package com.aly.sahlsync;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

final class BackupNotifications {
 private static final String STATUS="backup-status";
 private static final String RESULTS="backup-results";

 static boolean permitted(Context context){
  NotificationManager manager=context.getSystemService(NotificationManager.class);
  return manager.areNotificationsEnabled()&&(Build.VERSION.SDK_INT<33
   ||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
 }

 static void refreshStatus(Context context){
  NotificationManager manager=context.getSystemService(NotificationManager.class);
  if(!permitted(context)||!Store.prefs(context).getBoolean("pinnedStatus",false)){
   manager.cancel(1);
   return;
  }
  manager.createNotificationChannel(new NotificationChannel(STATUS,"Pinned backup status",NotificationManager.IMPORTANCE_LOW));
  post(context,null,1,builder(context,STATUS).setContentTitle("Sahl Sync")
   .setContentText("Tap to check your backups").setOngoing(true).setOnlyAlertOnce(true).build());
 }

 static void finished(Context context,String folderId,String folderName,boolean scheduled,String result){
  if(!permitted(context)||!Store.prefs(context).getBoolean("backupResults",true))return;
  NotificationManager manager=context.getSystemService(NotificationManager.class);
  manager.createNotificationChannel(new NotificationChannel(RESULTS,"Backup results",NotificationManager.IMPORTANCE_DEFAULT));
  post(context,folderId,2,builder(context,RESULTS)
   .setContentTitle((scheduled?"Scheduled backup: ":"Backup: ")+folderName)
   .setContentText(result).setStyle(new Notification.BigTextStyle().bigText(result))
   .setAutoCancel(true).build());
 }

 private static Notification.Builder builder(Context context,String channel){
  Intent open=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent action=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  return new Notification.Builder(context,channel).setSmallIcon(R.drawable.ic_backup_notification).setContentIntent(action);
 }

 private static void post(Context context,String tag,int id,Notification notification){
  try{context.getSystemService(NotificationManager.class).notify(tag,id,notification);}
  catch(SecurityException ignored){/* Permission may have changed since the check. */}
 }
}
