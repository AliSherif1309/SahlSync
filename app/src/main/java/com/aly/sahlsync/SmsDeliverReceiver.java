package com.aly.sahlsync;

import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * While Sahl Sync is temporarily the default SMS app (only during a restore), Android delivers new SMS here.
 * Each one is saved to the inbox so nothing is lost; switch the default back to your messages app afterwards.
 */
public final class SmsDeliverReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent){
  SmsMessage[] parts=Telephony.Sms.Intents.getMessagesFromIntent(intent);
  if(parts==null||parts.length==0)return;
  StringBuilder body=new StringBuilder();
  for(SmsMessage part:parts)body.append(part.getDisplayMessageBody());
  ContentValues values=new ContentValues();
  values.put(Telephony.Sms.ADDRESS,parts[0].getDisplayOriginatingAddress());
  values.put(Telephony.Sms.BODY,body.toString());
  values.put(Telephony.Sms.DATE,System.currentTimeMillis());
  values.put(Telephony.Sms.DATE_SENT,parts[0].getTimestampMillis());
  values.put(Telephony.Sms.READ,0);
  values.put(Telephony.Sms.SEEN,0);
  values.put(Telephony.Sms.TYPE,Telephony.Sms.MESSAGE_TYPE_INBOX);
  try{context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI,values);}catch(Exception ignored){}
 }
}
