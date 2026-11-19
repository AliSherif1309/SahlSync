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

 @NonNull @Override public Result doWork(){
  Context context=getApplicationContext();
  synchronized(LanWorker.class){
   BackupCancellation.bind(this::isStopped);
   try{
    LanPairs pairs=new LanPairs(context.getSharedPreferences("lan-sync",Context.MODE_PRIVATE));
    LanPairs.Pair pair=pairs.get(getInputData().getString("pair"));
    if(pair==null||!pair.ready()||pair.autoMinutes<=0)return Result.success();
    LanRunner.run(context,pairs,pair,label->{});
    return Result.success();
   }catch(Exception error){
    return Result.retry();
   }finally{BackupCancellation.clear();}
  }
 }
}
