package com.aly.sahlsync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import androidx.documentfile.provider.DocumentFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLHandshakeException;
import org.json.JSONObject;

public final class LanActivity extends Activity {
 private static final int CHOOSE_FOLDER=70;
 private static final ExecutorService executor=Executors.newSingleThreadExecutor();
 private static boolean running;
 private SharedPreferences settings;
 private TextView connection,folder,progress;
 private Button pair,pick,sync;

 @Override protected void onCreate(Bundle saved){
  super.onCreate(saved);
  settings=getSharedPreferences("lan-sync",MODE_PRIVATE);
  ScrollView scroll=new ScrollView(this);
  LinearLayout content=new LinearLayout(this);
  content.setOrientation(LinearLayout.VERTICAL);
  int padding=SahlTheme.dp(this,20);
  content.setPadding(padding,padding,padding,padding);
  scroll.addView(content);
  setContentView(scroll);
  content.addView(SahlTheme.label(this,"Local Wi-Fi sync",25,SahlTheme.TEXT));
  content.addView(SahlTheme.label(this,"Pair a PC, choose one phone folder, then sync while both devices are on the same Wi-Fi.",16,SahlTheme.MUTED));
  connection=SahlTheme.label(this,"",15,SahlTheme.MUTED);
  content.addView(connection);
  pair=SahlTheme.button(this,"Enter pairing code",()->enterCode(""));
  content.addView(pair);
  folder=SahlTheme.label(this,"",15,SahlTheme.MUTED);
  content.addView(folder);
  pick=SahlTheme.button(this,"Choose phone folder",()->startActivityForResult(
   new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
    |Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),CHOOSE_FOLDER));
  content.addView(pick);
  sync=SahlTheme.button(this,"Sync now",this::startSync);
  content.addView(sync);
  progress=SahlTheme.label(this,"",16,SahlTheme.TEXT);
  content.addView(progress);
  refresh();
 }

 @Override protected void onResume(){super.onResume();if(progress!=null)refresh();}

 private void refresh(){
  String code=settings.getString("code","");
  connection.setText(code.isEmpty()?"No PC paired":"PC pairing saved");
  String tree=settings.getString("tree","");
  DocumentFile selected=tree.isEmpty()?null:DocumentFile.fromTreeUri(this,Uri.parse(tree));
  folder.setText(selected==null?"No phone folder selected":"Phone folder: "+selected.getName());
  progress.setText(running?"Sync is running.":settings.getString("result",""));
  pair.setEnabled(!running);
  pick.setEnabled(!running);
  sync.setEnabled(!running&&!code.isEmpty()&&selected!=null);
 }

 private void enterCode(String value){
  EditText input=new EditText(this);
  input.setSingleLine(true);
  input.setHint("Pairing code from your PC");
  input.setText(value);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Pair with your PC").setView(input)
   .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
  dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
   String code=input.getText().toString().trim();
   try{
    LanPeer peer=new LanPeer(code);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    pair.setEnabled(false);
    pick.setEnabled(false);
    sync.setEnabled(false);
    executor.execute(()->{
     boolean verified;
     try{peer.verifyPairing();verified=true;}catch(Exception error){verified=false;}
     boolean ready=verified;
     runOnUiThread(()->{
      if(isDestroyed())return;
      refresh();
      if(!dialog.isShowing())return;
      if(!ready){
       input.setError("Could not verify the PC. Check Wi-Fi and the pairing code.");
       dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
       return;
      }
      if(!code.equals(settings.getString("code","")))settings.edit().putString("code",code)
       .remove("baseline").remove("result").apply();
      dialog.dismiss();
      refresh();
     });
    });
   }catch(Exception error){input.setError("Enter the full pairing code shown on your PC.");}
  }));
  dialog.show();
 }

 @SuppressWarnings("deprecation")
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request!=CHOOSE_FOLDER||result!=RESULT_OK||data==null||data.getData()==null)return;
  try{
   Uri uri=data.getData();
   int required=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
   if((data.getFlags()&required)!=required)throw new SecurityException();
   getContentResolver().takePersistableUriPermission(uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
   if(!uri.toString().equals(settings.getString("tree","")))settings.edit().putString("tree",uri.toString())
    .remove("baseline").remove("result").apply();
   refresh();
  }catch(SecurityException error){progress.setText("Choose a writable folder on your phone.");}
 }

 private void startSync(){
  if(running)return;
  running=true;
  refresh();
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  String code=settings.getString("code",""),tree=settings.getString("tree","");
  String saved=settings.getString("baseline","{}");
  executor.execute(()->{
   String summary;
   try{
    LanPeer peer=new LanPeer(code);
    LanFolderSync.Result result=new LanFolderSync(getApplicationContext(),peer,
     label->runOnUiThread(()->{if(!isDestroyed())progress.setText(label);})).run(Uri.parse(tree),new JSONObject(saved));
    settings.edit().putString("baseline",result.baseline.toString()).apply();
    summary=result.summary;
   }catch(SSLHandshakeException error){
    summary="Could not verify the PC identity. Check the pairing code.";
   }catch(java.net.ConnectException|java.net.SocketTimeoutException error){
    summary="Could not reach the PC. Check that the companion is running and both devices are on the same Wi-Fi.";
   }catch(Exception error){
    summary=error.getMessage();
    if(summary==null||summary.isEmpty())summary="Local sync could not finish.";
   }
   settings.edit().putString("result",summary).apply();
   runOnUiThread(()->{
    running=false;
    if(!isDestroyed()){
     getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
     refresh();
    }
   });
  });
 }
}
