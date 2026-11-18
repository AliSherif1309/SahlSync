package com.aly.sahlsync;

import android.content.Context;
import androidx.work.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class BackupSchedule {
 static String tag(String id){return "timed-schedule-"+id;}

 static long next(Instant after,ZoneId zone,int hour,int minute,int weekdays){
  LocalDate first=after.atZone(zone).toLocalDate();
  for(int day=0;day<=7;day++){
   LocalDate date=first.plusDays(day);
   if((weekdays&(1<<(date.getDayOfWeek().getValue()-1)))==0)continue;
   Instant candidate=date.atTime(hour,minute).atZone(zone).toInstant();
   if(candidate.isAfter(after))return candidate.toEpochMilli();
  }
  throw new IllegalArgumentException("Choose at least one weekday");
 }

 static void cancel(Context context,String id){
  WorkManager manager=WorkManager.getInstance(context);
  manager.cancelUniqueWork("scheduled-"+id);
  manager.cancelAllWorkByTag(tag(id));
 }

 static Operation enqueue(Context context,JSONObject pair,long due){
  String id=pair.optString("id");
  String generation=pair.optString("scheduleGeneration");
  Data input=new Data.Builder().putString("pair",id).putString("scheduleGeneration",generation)
   .putLong("scheduleDue",due).build();
  OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(BackupWorker.class)
   .addTag(tag(id)).addTag(generation).setInputData(input)
   .setInitialDelay(Math.max(0,due-System.currentTimeMillis()),TimeUnit.MILLISECONDS)
   .setConstraints(BackupWorker.constraints(pair)).build();
  return WorkManager.getInstance(context).enqueueUniqueWork(tag(id)+"-"+generation+"-"+due,
   ExistingWorkPolicy.KEEP,request);
 }
}
