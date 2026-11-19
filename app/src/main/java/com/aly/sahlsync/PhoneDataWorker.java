package com.aly.sahlsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

/** Automatic (and "Back up now") messages, call log and app list backup into the chosen folder. */
public final class PhoneDataWorker extends Worker {
 static final String NOW="phone-data-now",AUTO="phone-data-auto";
 public PhoneDataWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}

 static SharedPreferences prefs(Context c){return c.getSharedPreferences("phone-data",Context.MODE_PRIVATE);}

 static void runNow(Context c){
  WorkManager.getInstance(c).enqueueUniqueWork(NOW,ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(PhoneDataWorker.class).build());
 }

 static void schedule(Context c){
  int days=prefs(c).getInt("autoDays",0);
  if(days<=0||prefs(c).getString("folder",null)==null){WorkManager.getInstance(c).cancelUniqueWork(AUTO);return;}
  WorkManager.getInstance(c).enqueueUniquePeriodicWork(AUTO,ExistingPeriodicWorkPolicy.UPDATE,
   new PeriodicWorkRequest.Builder(PhoneDataWorker.class,days,TimeUnit.DAYS)
    .setConstraints(new Constraints.Builder().setRequiresBatteryNotLow(true).build()).build());
 }

 @NonNull @Override public Result doWork(){
  Context c=getApplicationContext();
  SharedPreferences p=prefs(c);
  BackupCancellation.bind(this::isStopped);
  try{
   String tree=p.getString("folder",null);
   DocumentFile folder=tree==null?null:DocumentFile.fromTreeUri(c,Uri.parse(tree));
   if(folder==null||!folder.canWrite())throw new java.io.IOException("Choose the backup folder again.");
   try{setForegroundAsync(BackupNotifications.running(c,0x2A12,Tr.t(c,"Messages & apps backup"))).get();}catch(Exception ignored){}
   StringBuilder result=new StringBuilder();
   PhoneData data=new PhoneData(c,label->setProgressAsync(new Data.Builder().putString("label",label).build()));
   boolean sms=p.getBoolean("sms",true)&&PhoneDataActivity.granted(c,android.Manifest.permission.READ_SMS);
   boolean calls=p.getBoolean("calls",true)&&PhoneDataActivity.granted(c,android.Manifest.permission.READ_CALL_LOG);
   if(sms||calls){
    result.append(data.exportMessages(folder,sms,calls));
    PhoneData.prune(folder,PhoneData.MESSAGES_PREFIX,p.getInt("keep",10));
   }
   if(p.getBoolean("apps",true)){
    if(result.length()>0)result.append('\n');
    result.append(data.exportApps(folder,p.getBoolean("apks",false)));
    PhoneData.prune(folder,PhoneData.APPS_PREFIX,p.getInt("keep",10));
   }
   p.edit().putString("last",result.toString()).putLong("lastTime",System.currentTimeMillis()).apply();
   return Result.success();
  }catch(Exception error){
   p.edit().putString("last",isStopped()?"Stopped.":"Backup failed: "+error.getMessage()).apply();
   return Result.failure();
  }finally{BackupCancellation.clear();}
 }
}
