package com.aly.sahlsync;

import android.app.*;
import android.content.*;

/** Once a day (battery-friendly inexact alarm): notify if a newer Sahl Sync release exists — same as Sahl Adhan. */
public final class UpdateCheckReceiver extends BroadcastReceiver {
 private static final int REQUEST=4501;

 static void schedule(Context context){
  Intent intent=new Intent(context,UpdateCheckReceiver.class);
  if(PendingIntent.getBroadcast(context,REQUEST,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_NO_CREATE)!=null)return;
  PendingIntent pending=PendingIntent.getBroadcast(context,REQUEST,intent,PendingIntent.FLAG_IMMUTABLE);
  context.getSystemService(AlarmManager.class).setInexactRepeating(AlarmManager.RTC,
   System.currentTimeMillis()+AlarmManager.INTERVAL_HOUR*3,AlarmManager.INTERVAL_DAY,pending);
 }

 @Override public void onReceive(Context context,Intent intent){
  PendingResult done=goAsync();
  Context app=context.getApplicationContext();
  new Thread(()->{try{check(app);}catch(Exception ignored){}finally{done.finish();}}).start();
 }

 private static void check(Context context)throws Exception{
  SharedPreferences prefs=context.getSharedPreferences("sahl_updates",Context.MODE_PRIVATE);
  long now=System.currentTimeMillis();
  if(now-prefs.getLong("lastUpdateCheck",0)<20L*3600_000L)return;
  prefs.edit().putLong("lastUpdateCheck",now).apply();
  ReleaseUpdates.Release newest=ReleaseUpdates.latest();
  if(newest==null||newest.code<=BuildConfig.VERSION_CODE||prefs.getLong("notifiedCode",0)==newest.code)return;
  prefs.edit().putLong("notifiedCode",newest.code).apply();
  NotificationManager manager=context.getSystemService(NotificationManager.class);
  manager.createNotificationChannel(new NotificationChannel("updates","Updates",NotificationManager.IMPORTANCE_DEFAULT));
  Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
  if(launch==null)return;
  PendingIntent open=PendingIntent.getActivity(context,5,launch,PendingIntent.FLAG_IMMUTABLE);
  try{
   manager.notify(500,SahlNotif.apply(context,new Notification.Builder(context,"updates"))
    .setContentTitle("New version available: v"+newest.version)
    .setContentText(newest.notes.isEmpty()?"Update now":newest.notes)
    .setContentIntent(open).setAutoCancel(true).build());
  }catch(SecurityException ignored){}
 }
}
