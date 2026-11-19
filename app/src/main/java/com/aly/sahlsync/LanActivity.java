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
import java.util.List;

public final class LanActivity extends Activity {
 private static final int CHOOSE_FOLDER=70, EXPORT_VERSION=71;
 private static final ExecutorService executor=Executors.newSingleThreadExecutor();
 private static boolean running;
 private SharedPreferences settings;
 private TextView connection,folder,progress;
 private Button pair,pick,sync,mode,history;
 private String pendingVersion;

 @Override protected void onCreate(Bundle saved){
  super.onCreate(saved);
  if(saved!=null)pendingVersion=saved.getString("pendingVersion");
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
  mode=SahlTheme.button(this,"",this::chooseMode);
  content.addView(mode);
  sync=SahlTheme.button(this,"Sync now",this::startSync);
  content.addView(sync);
  history=SahlTheme.button(this,"Version history",this::showVersions);
  content.addView(history);
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
  mode.setText("Sync mode: "+selectedMode().label);
  mode.setEnabled(!running);
  history.setEnabled(!running&&!code.isEmpty()&&selected!=null);
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
    mode.setEnabled(false);
    history.setEnabled(false);
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
       .remove("result").apply();
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
  if(request==EXPORT_VERSION){
   String version=pendingVersion;
   pendingVersion=null;
   if(version!=null&&result==RESULT_OK&&data!=null&&data.getData()!=null)exportVersion(version,data.getData());
   return;
  }
  if(request!=CHOOSE_FOLDER||result!=RESULT_OK||data==null||data.getData()==null)return;
  try{
   Uri uri=data.getData();
   int required=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
   if((data.getFlags()&required)!=required)throw new SecurityException();
   getContentResolver().takePersistableUriPermission(uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
   if(!uri.toString().equals(settings.getString("tree","")))settings.edit().putString("tree",uri.toString())
    .remove("result").apply();
   refresh();
  }catch(SecurityException error){progress.setText("Choose a writable folder on your phone.");}
 }

 @Override protected void onSaveInstanceState(Bundle out){
  super.onSaveInstanceState(out);
  out.putString("pendingVersion",pendingVersion);
 }

 private LanFolderSync.Mode selectedMode(){
  return LanFolderSync.Mode.from(settings.getString("mode",""));
 }

 private void chooseMode(){
  LanFolderSync.Mode[] modes=LanFolderSync.Mode.values();
  String[] labels=new String[modes.length];
  for(int i=0;i<modes.length;i++)labels[i]=modes[i].label;
  new AlertDialog.Builder(this).setTitle("Sync mode")
   .setSingleChoiceItems(labels,selectedMode().ordinal(),(dialog,index)->{
    settings.edit().putString("mode",modes[index].name()).apply();
    dialog.dismiss();
    refresh();
   }).setNegativeButton("Cancel",null).show();
 }

 private LanVersions versions()throws Exception{
  LanPeer peer=new LanPeer(settings.getString("code",""));
  return new LanVersions(getApplicationContext(),peer.identity,settings.getString("tree",""));
 }

 private void showVersions(){
  executor.execute(()->{
   try{
    List<LanVersions.Version> versions=versions().list();
    runOnUiThread(()->{
     if(isDestroyed())return;
     AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Version history");
     if(versions.isEmpty())dialog.setMessage("No older versions saved for this folder.");
     else{
      String[] labels=new String[versions.size()];
      java.text.DateFormat date=java.text.DateFormat.getDateTimeInstance();
      for(int i=0;i<labels.length;i++){
       LanVersions.Version version=versions.get(i);
       labels[i]=version.path+"\n"+version.side+" · "+date.format(new java.util.Date(version.savedAt));
      }
      dialog.setItems(labels,(ignored,index)->{
       LanVersions.Version version=versions.get(index);
       pendingVersion=version.id;
       String name=version.path.substring(version.path.lastIndexOf('/')+1);
       startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"older-"+name),EXPORT_VERSION);
      });
     }
     dialog.setNegativeButton("Close",null).show();
    });
   }catch(Exception error){
    runOnUiThread(()->{if(!isDestroyed())progress.setText("Could not open Version history.");});
   }
  });
 }

 private void exportVersion(String id,Uri destination){
  executor.execute(()->{
   String message="Could not export the older copy.";
   try{
    LanVersions historyStore=versions();
    for(LanVersions.Version version:historyStore.list()){
     if(!version.id.equals(id))continue;
     historyStore.export(getApplicationContext(),destination,version);
     message="Older copy saved.";
     break;
    }
   }catch(Exception ignored){/* Report export failure below. */}
   String result=message;
   runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,result,Toast.LENGTH_LONG).show();});
  });
 }

 private void startSync(){
  if(running)return;
  running=true;
  refresh();
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  String code=settings.getString("code",""),tree=settings.getString("tree","");
  LanFolderSync.Mode selected=selectedMode();
  executor.execute(()->{
   String summary;
   try{
    LanPeer peer=new LanPeer(code);
    summary=new LanFolderSync(getApplicationContext(),peer,selected,
     label->runOnUiThread(()->{if(!isDestroyed())progress.setText(label);})).run(Uri.parse(tree));
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
