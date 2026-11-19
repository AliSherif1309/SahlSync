package com.aly.sahlsync;

import android.content.*;

/** Required by Android for a default SMS app. MMS arriving during a short restore are downloaded later by your messages app. */
public final class MmsDeliverReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent){}
}
