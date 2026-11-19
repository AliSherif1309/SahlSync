package com.aly.sahlsync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Required by Android for a default SMS app ("respond via message" from the call screen). Not used. */
public final class RespondService extends Service {
 @Override public int onStartCommand(Intent intent,int flags,int startId){stopSelf(startId);return START_NOT_STICKY;}
 @Override public IBinder onBind(Intent intent){return null;}
}
