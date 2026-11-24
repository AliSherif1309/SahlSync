package com.aly.sahlsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Receives the OAuth 2.0 redirect URI from the browser for Google Drive sign-in. */
public final class GoogleAuthActivity extends Activity {
 @Override protected void onCreate(Bundle savedInstanceState){
  super.onCreate(savedInstanceState);
  process(getIntent());
 }

 @Override protected void onNewIntent(Intent intent){
  super.onNewIntent(intent);
  process(intent);
 }

 private void process(Intent intent){
  GoogleAuth.handleRedirect(this,intent==null?null:intent.getData());
  finish();
 }
}
