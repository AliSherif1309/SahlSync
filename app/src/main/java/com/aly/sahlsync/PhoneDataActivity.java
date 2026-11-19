package com.aly.sahlsync;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.View;
import android.widget.*;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import java.util.*;
import java.util.concurrent.*;

/** Messages, call log and app list backup and restore. Also opened if Android hands an SMS to Sahl Sync during a restore. */
public final class PhoneDataActivity extends Activity {
 private static final int FOLDER=1,PERMS=2,RESTORE_FILE=3,ROLE=4,APPS_FILE=5,CALL_PERMS=6;
 private static final int[] KEEP={3,5,10,30,0},AUTO={0,1,7,30};
 private SharedPreferences prefs;
 private TextView folderLabel,status,last;
 private Button backup,stop,nextApp;
 private ProgressBar bar;
 private Uri pendingRestore;
 private boolean restoreMessages,restoreCalls;
 private final Deque<PhoneData.App> missing=new ArrayDeque<>();
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private androidx.lifecycle.LiveData<List<WorkInfo>> watched;
 private androidx.lifecycle.Observer<List<WorkInfo>> watcher;

 static boolean granted(Context c,String permission){return c.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}

 @Override protected void onCreate(Bundle state){
  super.onCreate(state);
  prefs=PhoneDataWorker.prefs(this);
  if(Intent.ACTION_SENDTO.equals(getIntent().getAction())||Intent.ACTION_SEND.equals(getIntent().getAction())){
   showSwitchBack();
   return;
  }
  LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
  int pad=SahlTheme.dp(this,20);content.setPadding(pad,pad,pad,pad);
  ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(SahlTheme.TOP);scroll.addView(content);
  setContentView(scroll);

  content.addView(SahlTheme.label(this,"Messages & apps",26,SahlTheme.TEXT));
  content.addView(SahlTheme.label(this,"Back up your SMS, MMS, call log and app list. Choose a folder that Sahl Sync already syncs, so backups reach OneDrive or your PC automatically.",14,SahlTheme.MUTED));

  LinearLayout where=SahlTheme.card(this);content.addView(where);
  where.addView(SahlTheme.label(this,"Backup folder",16,SahlTheme.TEXT));
  folderLabel=SahlTheme.label(this,"",14,SahlTheme.MUTED);where.addView(folderLabel);
  where.addView(SahlTheme.secondary(this,"Choose backup folder",()->{
   startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
    |Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),FOLDER);
  }));

  LinearLayout what=SahlTheme.card(this);content.addView(what);
  what.addView(SahlTheme.label(this,"What to back up",16,SahlTheme.TEXT));
  what.addView(check("SMS & MMS (with attachments)","sms",true));
  what.addView(check("Call log","calls",true));
  what.addView(check("App list (to reinstall later)","apps",true));
  what.addView(check("Also save APK files (bigger; for apps not from Play Store)","apks",false));
  what.addView(SahlTheme.label(this,"Backups to keep",15,SahlTheme.TEXT));
  what.addView(spinner(new String[]{"3","5","10","30","All"},KEEP,"keep",10));
  what.addView(SahlTheme.label(this,"Automatic backup",15,SahlTheme.TEXT));
  what.addView(spinner(new String[]{"Off","Every day","Every week","Every month"},AUTO,"autoDays",0));

  backup=SahlTheme.button(this,"Back up now",this::startBackup);content.addView(backup);
  bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setIndeterminate(true);
  bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(SahlTheme.GOLD));bar.setVisibility(View.GONE);content.addView(bar);
  status=SahlTheme.label(this,"",14,SahlTheme.TEXT);content.addView(status);
  stop=SahlTheme.secondary(this,"Stop",()->WorkManager.getInstance(this).cancelUniqueWork(PhoneDataWorker.NOW));
  stop.setVisibility(View.GONE);content.addView(stop);
  last=SahlTheme.label(this,"",13,SahlTheme.MUTED);content.addView(last);

  LinearLayout restore=SahlTheme.card(this);content.addView(restore);
  restore.addView(SahlTheme.label(this,"Restore",16,SahlTheme.TEXT));
  restore.addView(SahlTheme.label(this,"Messages already on the phone are skipped, so restoring twice does not create duplicates.",13,SahlTheme.MUTED));
  restore.addView(SahlTheme.secondary(this,"Restore messages & calls",()->pick(RESTORE_FILE,"application/zip")));
  restore.addView(SahlTheme.secondary(this,"Reinstall apps from a list",()->pick(APPS_FILE,"application/json")));
  nextApp=SahlTheme.button(this,"",this::openNextApp);nextApp.setVisibility(View.GONE);restore.addView(nextApp);

  (watched=WorkManager.getInstance(getApplicationContext()).getWorkInfosForUniqueWorkLiveData(PhoneDataWorker.NOW)).observeForever(watcher=infos->{
   boolean running=false;String label=null;
   for(WorkInfo info:infos){if(!info.getState().isFinished())running=true;String v=info.getProgress().getString("label");if(v!=null)label=v;}
   setRunning(running,label);
  });
  refresh();
 }

 @Override protected void onDestroy(){if(watched!=null&&watcher!=null)watched.removeObserver(watcher);worker.shutdownNow();super.onDestroy();}
 @Override protected void onResume(){super.onResume();if(prefs!=null&&folderLabel!=null)refresh();}

 private CheckBox check(String text,String key,boolean fallback){
  CheckBox box=new CheckBox(this);box.setText(Tr.t(this,text));box.setTextColor(SahlTheme.TEXT);box.setChecked(prefs.getBoolean(key,fallback));
  box.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean(key,on).apply());
  return box;
 }

 private Spinner spinner(String[] labels,int[] values,String key,int fallback){
  Spinner spinner=new Spinner(this);
  spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,Tr.all(this,labels)));
  int current=prefs.getInt(key,fallback);
  for(int i=0;i<values.length;i++)if(values[i]==current)spinner.setSelection(i);
  spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
   @Override public void onItemSelected(AdapterView<?> p,View v,int position,long id){
    prefs.edit().putInt(key,values[position]).apply();PhoneDataWorker.schedule(getApplicationContext());
   }
   @Override public void onNothingSelected(AdapterView<?> p){}
  });
  return spinner;
 }

 private void refresh(){
  String tree=prefs.getString("folder",null);
  DocumentFile folder=tree==null?null:DocumentFile.fromTreeUri(this,Uri.parse(tree));
  folderLabel.setText(folder==null?Tr.t(this,"No folder chosen"):String.valueOf(folder.getName()));
  long time=prefs.getLong("lastTime",0);
  String result=prefs.getString("last","");
  last.setText(result.isEmpty()?"":(time>0?Tr.t(this,"Last backup: ")+android.text.format.DateFormat.getDateFormat(this).format(new Date(time))
   +" "+android.text.format.DateFormat.getTimeFormat(this).format(new Date(time))+"\n":"")+result);
  Tr.tree(this,getWindow().getDecorView());
 }

 private void setRunning(boolean running,String label){
  backup.setEnabled(!running);backup.setAlpha(running?0.4f:1f);
  bar.setVisibility(running?View.VISIBLE:View.GONE);stop.setVisibility(running?View.VISIBLE:View.GONE);
  status.setText(running?(label==null?Tr.t(this,"Starting…"):label):"");
  if(!running)refresh();
 }

 // ------------------------------------------------ backup

 private void startBackup(){
  if(prefs.getString("folder",null)==null){toast("Choose a backup folder first.");return;}
  List<String> needed=new ArrayList<>();
  if(prefs.getBoolean("sms",true)&&!granted(this,Manifest.permission.READ_SMS))needed.add(Manifest.permission.READ_SMS);
  if(prefs.getBoolean("calls",true)&&!granted(this,Manifest.permission.READ_CALL_LOG))needed.add(Manifest.permission.READ_CALL_LOG);
  if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),PERMS);return;}
  PhoneDataWorker.runNow(getApplicationContext());
 }

 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
  super.onRequestPermissionsResult(request,permissions,results);
  if(request==PERMS)PhoneDataWorker.runNow(getApplicationContext());   // Items without permission are skipped.
  if(request==CALL_PERMS)beginRestore();
 }

 // ------------------------------------------------ restore messages & calls

 private void pick(int request,String mime){
  startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime.equals("application/zip")?"*/*":mime),request);
 }

 private void askWhatToRestore(Uri file){
  pendingRestore=file;
  boolean[] choice={true,true};
  Tr.builder(this).setTitle("Restore")
   .setMultiChoiceItems(Tr.all(this,new String[]{"SMS & MMS","Call log"}),choice,(d,w,on)->choice[w]=on)
   .setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->{
    restoreMessages=choice[0];restoreCalls=choice[1];
    if(restoreCalls&&!granted(this,Manifest.permission.WRITE_CALL_LOG)){
     requestPermissions(new String[]{Manifest.permission.WRITE_CALL_LOG,Manifest.permission.READ_CALL_LOG},CALL_PERMS);return;
    }
    beginRestore();
   }).show();
 }

 private boolean isDefaultSms(){return getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(this));}

 private void beginRestore(){
  if(restoreMessages&&!isDefaultSms()){
   Tr.builder(this).setTitle("One step from Android")
    .setMessage("Android lets only the default SMS app restore messages. Sahl Sync will become the SMS app for the restore; afterwards it takes you to switch back to your messages app. SMS that arrive meanwhile are kept.")
    .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->{
     prefs.edit().putString("previousSms",Telephony.Sms.getDefaultSmsPackage(this)).apply();
     if(Build.VERSION.SDK_INT>=29){
      RoleManager roles=getSystemService(RoleManager.class);
      startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_SMS),ROLE);
     }else{
      startActivityForResult(new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,getPackageName()),ROLE);
     }
    }).show();
   return;
  }
  runRestore();
 }

 private void runRestore(){
  Uri file=pendingRestore;boolean messages=restoreMessages&&isDefaultSms(),calls=restoreCalls&&granted(this,Manifest.permission.WRITE_CALL_LOG);
  setRunning(true,Tr.t(this,"Restoring…"));stop.setVisibility(View.GONE);
  worker.execute(()->{
   String result;
   try{result=new PhoneData(getApplicationContext(),label->runOnUiThread(()->status.setText(label))).importMessages(file,messages,calls);}
   catch(Exception e){result="Restore failed: "+e.getMessage();}
   String done=result;
   runOnUiThread(()->{
    if(isDestroyed())return;
    setRunning(false,null);
    prefs.edit().putString("last",done).apply();refresh();
    if(isDefaultSms())showSwitchBack();
    else Tr.builder(this).setTitle("Restore").setMessage(done).setPositiveButton("Close",null).show();
   });
  });
 }

 /** After a restore (or if a messages app action lands here): send the user to pick their messages app again. */
 private void showSwitchBack(){
  String previous=prefs.getString("previousSms",null);
  String name=previous;
  try{name=String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(previous,0)));}catch(Exception ignored){}
  Tr.builder(this).setTitle("Switch back to your messages app")
   .setMessage(Tr.t(this,"Restore is done. Choose your messages app as the default SMS app again")+(name==null?".":": "+name+"."))
   .setCancelable(false).setPositiveButton("Open settings",(d,w)->{
    if(Build.VERSION.SDK_INT<29&&previous!=null){
     startActivity(new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,previous));
    }else{
     try{startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));}
     catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}
    }
   }).show();
 }

 // ------------------------------------------------ apps

 private void showAppList(Uri file){
  worker.execute(()->{
   try{
    List<PhoneData.App> apps=new PhoneData(getApplicationContext(),l->{}).readAppList(file);
    List<PhoneData.App> notInstalled=new ArrayList<>();
    for(PhoneData.App a:apps)if(!a.installed)notInstalled.add(a);
    runOnUiThread(()->chooseApps(apps.size(),notInstalled));
   }catch(Exception e){runOnUiThread(()->toast("This is not a Sahl Sync app list."));}
  });
 }

 private void chooseApps(int total,List<PhoneData.App> notInstalled){
  if(notInstalled.isEmpty()){toast(Tr.t(this,"All apps in the list are already installed."));return;}
  String[] names=new String[notInstalled.size()];boolean[] chosen=new boolean[names.length];
  for(int i=0;i<names.length;i++){names[i]=notInstalled.get(i).name;chosen[i]=true;}
  Tr.builder(this).setTitle(Tr.t(this,"Missing apps: ")+names.length+" / "+total)
   .setMultiChoiceItems(names,chosen,(d,w,on)->chosen[w]=on)
   .setNegativeButton("Cancel",null).setPositiveButton("Install",(d,w)->{
    missing.clear();
    for(int i=0;i<names.length;i++)if(chosen[i])missing.add(notInstalled.get(i));
    openNextApp();
   }).show();
 }

 /** Opens each missing app's store page in turn; come back and tap the button for the next one. */
 private void openNextApp(){
  PhoneData.App app=missing.pollFirst();
  if(app==null){nextApp.setVisibility(View.GONE);return;}
  nextApp.setText(Tr.t(this,"Next app")+" ("+missing.size()+")");
  nextApp.setVisibility(missing.isEmpty()?View.GONE:View.VISIBLE);
  try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+app.packageName)));}
  catch(ActivityNotFoundException e){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id="+app.packageName)));}
 }

 // ------------------------------------------------ results

 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==ROLE){
   if(isDefaultSms())runRestore();else if(restoreCalls)runRestoreCallsOnly();
   return;
  }
  if(result!=RESULT_OK||data==null||data.getData()==null)return;
  Uri uri=data.getData();
  if(request==FOLDER){
   getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
   prefs.edit().putString("folder",uri.toString()).apply();
   PhoneDataWorker.schedule(getApplicationContext());
   refresh();
  }else if(request==RESTORE_FILE)askWhatToRestore(uri);
  else if(request==APPS_FILE)showAppList(uri);
 }

 private void runRestoreCallsOnly(){restoreMessages=false;runRestore();}

 private void toast(String text){Toast.makeText(this,Tr.t(this,text),Toast.LENGTH_LONG).show();}
}
