package com.aly.sahlsync;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.work.ForegroundInfo;

final class BackupNotifications {
 private static final String STATUS="backup-status";
 private static final String RESULTS="backup-results";
 private static final String RUNNING="backup-running";

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

 static ForegroundInfo running(Context context,int id,String folderName){
  NotificationManager manager=context.getSystemService(NotificationManager.class);
  manager.createNotificationChannel(new NotificationChannel(RUNNING,"Backup in progress",NotificationManager.IMPORTANCE_LOW));
  Notification notification=builder(context,RUNNING).setContentTitle("Backing up: "+folderName)
   .setContentText("You can leave Sahl Sync while this backup runs.")
   .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
  return Build.VERSION.SDK_INT>=29
   ?new ForegroundInfo(id,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
   :new ForegroundInfo(id,notification);
 }

 /** Wi-Fi sync running in the background: live progress and a Stop button in the notification. */
 static ForegroundInfo lanRunning(Context context,int id,String title,String text,int done,int todo,PendingIntent stop){
  NotificationManager manager=context.getSystemService(NotificationManager.class);
  manager.createNotificationChannel(new NotificationChannel(RUNNING,"Backup in progress",NotificationManager.IMPORTANCE_LOW));
  Intent open=new Intent(context,LanActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
  Notification notification=new Notification.Builder(context,RUNNING).setSmallIcon(R.drawable.ic_backup_notification)
   .setContentIntent(PendingIntent.getActivity(context,1,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT))
   .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
   .setProgress(Math.max(todo,0),Math.max(done,0),todo<=0).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
   .addAction(new Notification.Action.Builder(null,Tr.t(context,"Stop"),stop).build()).build();
  return Build.VERSION.SDK_INT>=29
   ?new ForegroundInfo(id,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
   :new ForegroundInfo(id,notification);
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
