package com.aly.sahlsync;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

/** Background Wi-Fi sync for pairs with Auto sync turned on. Runs only on Wi-Fi. */
public final class LanWorker extends Worker {
 public LanWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}

 static void schedule(Context context,LanPairs.Pair pair){
  WorkManager manager=WorkManager.getInstance(context);
  String name="lan-auto-"+pair.id;
  if(pair.autoMinutes<=0||!pair.ready()){manager.cancelUniqueWork(name);return;}
  Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
   .setRequiresBatteryNotLow(true).setRequiresCharging(pair.autoCharging).build();
  manager.enqueueUniquePeriodicWork(name,ExistingPeriodicWorkPolicy.UPDATE,
   new PeriodicWorkRequest.Builder(LanWorker.class,Math.max(15,pair.autoMinutes),TimeUnit.MINUTES)
    .setConstraints(constraints).setInputData(new Data.Builder().putString("pair",pair.id).build()).build());
 }

 static final String MANUAL="lan-manual";

 /** "Sync now": runs as a background job, so it keeps going after you leave the app. */
 static void runNow(Context context,java.util.List<String> ids){
  Data data=new Data.Builder().putStringArray("pairs",ids.toArray(new String[0])).putBoolean("manual",true).build();
  WorkManager.getInstance(context).enqueueUniqueWork(MANUAL,ExistingWorkPolicy.KEEP,
   new OneTimeWorkRequest.Builder(LanWorker.class).setInputData(data).build());
 }

 private long lastUpdate;
 private void show(Context context,String title,String text){
  setProgressAsync(new Data.Builder().putString("label",title+"\n"+Tr.t(context,text)).build());
  if(System.currentTimeMillis()-lastUpdate<1000)return;
  lastUpdate=System.currentTimeMillis();
  try{
   setForegroundAsync(BackupNotifications.lanRunning(context,0x2A11,Tr.t(context,"Syncing with PC: ")+title,Tr.t(context,text),
    LanFolderSync.done,LanFolderSync.todo,WorkManager.getInstance(context).createCancelPendingIntent(getId())));
  }catch(Exception ignored){/* Notification is optional; the sync continues. */}
 }

 @NonNull @Override public Result doWork(){
  Context context=getApplicationContext();
  boolean manual=getInputData().getBoolean("manual",false);
  synchronized(LanWorker.class){
   BackupCancellation.bind(this::isStopped);
   try{
    LanPairs pairs=new LanPairs(context.getSharedPreferences("lan-sync",Context.MODE_PRIVATE));
    String[] ids=manual?getInputData().getStringArray("pairs"):new String[]{getInputData().getString("pair")};
    if(ids==null)return Result.success();
    for(String id:ids){
     if(isStopped())break;
     LanPairs.Pair pair=pairs.get(id);
     if(pair==null||!pair.ready()||(!manual&&pair.autoMinutes<=0))continue;
     if(manual){lastUpdate=0;show(context,pair.name,"Starting…");}
     LanRunner.run(context,pairs,pair,label->{if(manual)show(context,pair.name,label);});
     if(isStopped()){pair.result=Tr.t(context,"Stopped. The next sync continues where it left off.");pairs.update(pair);}
    }
    return Result.success();
   }catch(Exception error){
    return manual?Result.failure():Result.retry();
   }finally{BackupCancellation.clear();}
  }
 }
}
