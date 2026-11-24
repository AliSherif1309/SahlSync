package com.aly.sahlsync;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import androidx.work.*;
import com.microsoft.identity.client.*;
import com.microsoft.identity.client.exception.MsalException;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
 private final ExecutorService io=Executors.newSingleThreadExecutor();
 private IMultipleAccountPublicClientApplication auth;
 private List<IAccount> accounts=Collections.emptyList();
 private final Map<TextView,JSONObject> ownerLabels=new LinkedHashMap<>();
 private LinearLayout content;
 private TextView status,history;
 private Button connectButton,disconnectButton;
 private final List<Runnable> unwatch=new ArrayList<>();
 private final android.content.SharedPreferences.OnSharedPreferenceChangeListener historyListener=(prefs,key)->{if("historyVersion".equals(key)||"history".equals(key))runOnUiThread(this::refreshHistory);};
 private String pendingLocal;
 private String pendingWriteTree;
 private String folderQuery="";
 private String openFolder;
 private java.io.File pendingSnapshot;
 private String pendingSnapshotName;
 @Override public void onCreate(Bundle saved){
  SahlCore.catchCrashes(this);
  super.onCreate(saved);if(saved!=null){pendingLocal=saved.getString("local");pendingWriteTree=saved.getString("writePair");}
  if(saved!=null){String cached=saved.getString("snapshot");if(cached!=null){java.io.File file=new java.io.File(cached);try{if(file.getCanonicalFile().getParentFile().equals(getCacheDir().getCanonicalFile()))pendingSnapshot=file;}catch(java.io.IOException ignored){}}pendingSnapshotName=saved.getString("snapshotName");}
  Store.prefs(this).registerOnSharedPreferenceChangeListener(historyListener);
  draw();
  if(saved==null){
   if(!Store.prefs(this).getBoolean("setupSeen",false))showBackgroundSetup();
   else offerBackgroundSetupForExistingSchedules();
  }
  if(Auth.configured())io.execute(()->{try{IMultipleAccountPublicClientApplication app=Auth.open(getApplicationContext());runOnUiThread(()->{auth=app;loadAccount();});}catch(Exception e){runOnUiThread(()->{status.setText("Could not initialize Microsoft sign-in. Reopen the app to retry.");});}});
 }
 @Override protected void onResume(){super.onResume();BackupNotifications.refreshStatus(this);if(auth!=null)loadAccount();
  UpdateCheckReceiver.schedule(this);if(!updateCheckedThisRun){updateCheckedThisRun=true;checkForUpdates(null);}}
 private void offerBackgroundSetupForExistingSchedules(){
  if(backgroundAllowed()||Store.prefs(this).getBoolean("backgroundSetupOffered",false))return;
  try{
   JSONArray pairs=Store.pairs(this);
   for(int index=0;index<pairs.length();index++){
    JSONObject pair=pairs.getJSONObject(index);
    if(pair.optBoolean("daily")||pair.optInt("scheduleMode")>=2
     ||(pair.optInt("scheduleMode")==1&&pair.optLong("scheduleDate")>System.currentTimeMillis())){
     offerBackgroundSetup();
     break;
    }
   }
  }catch(Exception ignored){/* draw() reports unreadable folder settings. */}
 }

 private boolean backgroundAllowed(){
  return getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
 }

 private void offerBackgroundSetup(){
  if(backgroundAllowed())return;
  Store.prefs(this).edit().putBoolean("backgroundSetupOffered",true).apply();
  Tr.builder(this).setTitle("Allow background backups?")
   .setMessage("Some phones block OneDrive connections when Sahl Sync is in the background. Allow background backups to help your schedules run. This setting applies to Sahl Sync only.")
   .setNegativeButton("Not now",null)
   .setPositiveButton("Continue",(dialog,button)->openBackgroundPermission()).show();
 }

 private void openBackgroundPermission(){
  try{
   startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Uri.fromParts("package",getPackageName(),null)));
  }catch(ActivityNotFoundException|SecurityException unavailable){
   try{
    startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
   }catch(ActivityNotFoundException|SecurityException ignored){
    message("In Android Settings, search for Battery optimization and allow Sahl Sync to run in the background.");
   }
  }
 }

 private void showBackgroundSetup(){
  Store.prefs(this).edit().putBoolean("setupSeen",true).apply();
  LinearLayout form=new LinearLayout(this);
  form.setOrientation(LinearLayout.VERTICAL);
  int pad=SahlTheme.dp(this,20);
  form.setPadding(pad,pad,pad,pad);
  form.addView(SahlTheme.label(this,"Allow notifications for backup results and an optional pinned status. Folder access is requested when you choose a backup folder.",15,SahlTheme.TEXT));
  CheckBox results=new CheckBox(this);
  results.setText("Notify when a backup finishes");
  results.setChecked(Store.prefs(this).getBoolean("backupResults",true));
  form.addView(results);
  CheckBox pinned=new CheckBox(this);
  pinned.setText("Pin a shortcut to Sahl Sync");
  pinned.setChecked(Store.prefs(this).getBoolean("pinnedStatus",false));
  form.addView(pinned);
  if(backgroundAllowed()){
   form.addView(SahlTheme.label(this,"Battery optimization exemption is enabled.",14,SahlTheme.MUTED));
  }else{
   form.addView(SahlTheme.button(this,"Allow background backups",()->{
    saveNotificationOptions(results.isChecked(),pinned.isChecked());
    openBackgroundPermission();
   }));
  }
  form.addView(SahlTheme.label(this,"If backups are still delayed, check your phone's background app settings.",14,SahlTheme.MUTED));
  ScrollView scroll=new ScrollView(this);
  scroll.addView(form);
  Tr.builder(this).setTitle("Background & notifications").setView(scroll)
   .setNegativeButton("Close",null)
   .setNeutralButton("App settings",(dialog,button)->{
    saveNotificationOptions(results.isChecked(),pinned.isChecked());
    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getPackageName(),null)));
   })
   .setPositiveButton("Save",(dialog,button)->{
    saveNotificationOptions(results.isChecked(),pinned.isChecked());
    if((results.isChecked()||pinned.isChecked())&&!BackupNotifications.permitted(this)){
     if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},610);
     else startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));
    }
   }).show();
 }

 private void saveNotificationOptions(boolean results,boolean pinned){
  Store.prefs(this).edit().putBoolean("backupResults",results).putBoolean("pinnedStatus",pinned).apply();
  BackupNotifications.refreshStatus(this);
 }

 @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
  super.onRequestPermissionsResult(requestCode,permissions,results);
  if(requestCode==610)BackupNotifications.refreshStatus(this);
 }
 private static boolean updateCheckedThisRun=false;

 /** Sahl Adhan way: checks on open, clear result on manual check, "Update now" downloads the APK in the browser. */
 private void checkForUpdates(Button button){
  boolean manual=button!=null;
  if(manual){button.setEnabled(false);button.setText("Checking...");}
  io.execute(()->{
   ReleaseUpdates.Release found=null;boolean failed=false;
   try{found=ReleaseUpdates.latest();}catch(Exception error){failed=true;}
   ReleaseUpdates.Release newest=found;boolean error=failed;
   runOnUiThread(()->{
    if(isFinishing()||isDestroyed())return;
    if(manual){button.setEnabled(true);button.setText("Check for updates");}
    if(error){if(manual)message("Could not check GitHub for updates. Check your connection and try again.");return;}
    if(newest==null||newest.code<=BuildConfig.VERSION_CODE){if(manual)message("You have the latest version (v"+BuildConfig.VERSION_NAME+")");return;}
    offerUpdate(newest);
   });
  });
 }

 private void offerUpdate(ReleaseUpdates.Release newest){
  Tr.builder(this).setTitle("New version available: v"+newest.version)
   .setMessage(newest.notes.isEmpty()?"A new version of Sahl Sync is ready.":newest.notes)
   .setNegativeButton("Later",null)
   .setPositiveButton("Update now",(dialog,which)->{
    try{
     startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(newest.apkUrl)));
     android.widget.Toast.makeText(this,"Downloading in your browser — tap the download when it finishes to install",android.widget.Toast.LENGTH_LONG).show();
    }catch(ActivityNotFoundException error){message("Open this link in a browser: "+newest.apkUrl);}
   }).show();
 }

 private void loadAccount(){
  IMultipleAccountPublicClientApplication session=auth;
  if(session==null)return;
  io.execute(()->{
   try{
    List<IAccount> loaded=session.getAccounts();
    runOnUiThread(()->{
     if(isDestroyed()||isFinishing())return;
     accounts=new ArrayList<>(loaded);
     status.setText(accounts.isEmpty()?"No Microsoft accounts connected":accounts.size()+" connected Microsoft account"+(accounts.size()==1?"":"s"));
     updateAccountButtons();
    });
   }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())status.setText("Could not read Microsoft accounts. Try again.");});}
  });
 }

 private IAccount connectedAccount(String id){
  for(IAccount candidate:accounts)if(id.equals(candidate.getId()))return candidate;
  return null;
 }

 private String accountLabel(IAccount account){
  String username=account.getUsername();
  return username==null||username.isEmpty()?"Microsoft account":username;
 }

 private void selectAccount(String title,boolean autoSelect,java.util.function.Consumer<IAccount> action){
  List<IAccount> choices=new ArrayList<>(accounts);
  if(choices.isEmpty()){message("Connect a Microsoft account first.");return;}
  if(autoSelect&&choices.size()==1){action.accept(choices.get(0));return;}
  String[] labels=new String[choices.size()];
  for(int index=0;index<labels.length;index++)labels[index]=accountLabel(choices.get(index));
  Tr.builder(this).setTitle(title).setItems(labels,(dialog,index)->action.accept(choices.get(index)))
   .setNegativeButton("Cancel",null).show();
 }
 private LinearLayout card(String title){
  LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);int p=SahlTheme.dp(this,18);card.setPadding(p,p,p,p);card.setBackground(SahlTheme.rounded(this,SahlTheme.PANEL));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=SahlTheme.dp(this,16);content.addView(card,lp);
  card.addView(SahlTheme.label(this,title,21,SahlTheme.TEXT));return card;
 }
 private void draw(){
  ownerLabels.clear();
  for(Runnable remove:unwatch)remove.run();unwatch.clear();
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
  scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{SahlTheme.TOP,SahlTheme.BOTTOM}));
  scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setFocusableInTouchMode(true);int p=SahlTheme.dp(this,20);content.setPadding(p,p,p,p);scroll.addView(content);setContentView(scroll);content.requestFocus();
  content.addView(SahlTheme.label(this,"Sahl Sync",30,SahlTheme.TEXT));
  content.addView(SahlTheme.label(this,"Keep your phone, OneDrive and PC in sync.",15,SahlTheme.MUTED));
  LinearLayout connect=card("Microsoft accounts");status=SahlTheme.label(this,Auth.configured()?"Loading account...":"Microsoft sign-in setup is pending for this build.",16,SahlTheme.MUTED);connect.addView(status);
  connectButton=SahlTheme.button(this,"Connect OneDrive",this::connect);connect.addView(connectButton);
  disconnectButton=SahlTheme.secondary(this,"Disconnect an account",this::disconnect);connect.addView(disconnectButton);updateAccountButtons();
  connect.addView(SahlTheme.secondary(this,"Background & notifications",this::showBackgroundSetup));
  Button updates=SahlTheme.secondary(this,"Check for updates",()->{});
  updates.setOnClickListener(view->checkForUpdates(updates));
  connect.addView(updates);
  connect.addView(SahlTheme.secondary(this,"Language / اللغة",this::chooseLanguage));
  content.addView(SahlTheme.button(this,"Sync with my PC (Wi-Fi)",()->startActivity(new Intent(this,LanActivity.class))));
  content.addView(SahlTheme.secondary(this,"Messages & apps backup",()->startActivity(new Intent(this,PhoneDataActivity.class))));
  LinearLayout pairs=card("Folder backups");
  pairs.addView(SahlTheme.label(this,"Tap a folder to sync it or change its settings. Replaced and deleted files are kept in Version history.",14,SahlTheme.MUTED));
  pairs.addView(SahlTheme.button(this,"Add folder backup",()->{
   if(accounts.isEmpty()){message("Connect OneDrive first.");return;}
   pickPhoneFolder(42,null);
  }));
  try{
   JSONArray all=Store.pairs(this);
   EditText search=new EditText(this);
   search.setSingleLine(true);
   search.setHint("Search folder backups");
   search.setText(folderQuery);
   if(all.length()>1)pairs.addView(search);
   Map<View,String> searchable=new LinkedHashMap<>();
   if(all.length()==0)pairs.addView(SahlTheme.label(this,"No folders yet. Choose a folder on your phone, then a destination in OneDrive.",16,SahlTheme.MUTED));
   for(int i=0;i<all.length();i++){
    JSONObject pair=all.getJSONObject(i);
    String id=pair.getString("id");
    String title=pair.getString("name");
    LinearLayout entry=new LinearLayout(this);
    entry.setOrientation(LinearLayout.VERTICAL);
    int inner=SahlTheme.dp(this,12);entry.setPadding(inner,inner,inner,inner);
    entry.setBackground(SahlTheme.rounded(this,android.graphics.Color.parseColor("#183029")));
    LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=SahlTheme.dp(this,10);entry.setLayoutParams(gap);
    pairs.addView(entry);
    searchable.put(entry,(title+" "+pair.optString("remoteName")).toLowerCase(Locale.ROOT));
    LinearLayout actions=new LinearLayout(this);
    actions.setOrientation(LinearLayout.VERTICAL);
    actions.setVisibility(id.equals(openFolder)?View.VISIBLE:View.GONE);
    Button heading=SahlTheme.secondary(this,"",()->{});
    heading.setAllCaps(false);
    heading.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    heading.setText(title+(id.equals(openFolder)?"  ▴":"  ▾"));
    heading.setOnClickListener(view->{
     boolean expanded=actions.getVisibility()!=View.VISIBLE;
     for(View other:searchable.keySet()){
      if(other!=entry){
       LinearLayout row=(LinearLayout)other;
       row.getChildAt(row.getChildCount()-1).setVisibility(View.GONE);
       Button otherHeading=(Button)row.getChildAt(0);
       otherHeading.setText(otherHeading.getText().toString().replaceFirst("  ▴$","  ▾"));
      }
     }
     openFolder=expanded?id:null;
     actions.setVisibility(expanded?View.VISIBLE:View.GONE);
     heading.setText(title+(expanded?"  ▴":"  ▾"));
    });
    entry.addView(heading);
    entry.addView(SahlTheme.label(this,directionLabel(pair)+" / "+pair.optString("remoteName")+"\n"+(pair.optBoolean("unmetered")?"Unmetered network":"Any network")+(pair.optBoolean("charging")?" / Charging":"")+(pair.optBoolean("daily")||pair.optInt("scheduleMode")!=0?" / Scheduled":" / Manual"),14,SahlTheme.MUTED));
    TextView owner=SahlTheme.label(this,"",14,SahlTheme.MUTED);
    entry.addView(owner);
    ownerLabels.put(owner,pair);
    updateOwnerLabel(owner,pair);
    TextView manual=SahlTheme.label(this,"",14,SahlTheme.MUTED),scheduled=SahlTheme.label(this,"",14,SahlTheme.MUTED);
    entry.addView(manual);entry.addView(scheduled);entry.addView(actions);
    Button backup=SahlTheme.button(this,"Sync now",()->BackupWorker.enqueue(this,pair,false));actions.addView(backup);
    Button cancel=SahlTheme.secondary(this,"Stop",()->cancelBackup(pair));actions.addView(cancel);
    watchWork("manual-"+pair.optString("id"),manual,false,false);
    String generation=pair.optString("scheduleGeneration");
    watchWork(generation.isEmpty()?"scheduled-"+id:generation,scheduled,true,!generation.isEmpty());
    actions.addView(SahlTheme.secondary(this,"Settings",()->editPair(pair)));
    actions.addView(SahlTheme.secondary(this,"Activity",()->showFolderHistory(pair)));
    actions.addView(SahlTheme.secondary(this,"Version history",()->showVersionFiles(pair)));
    Button remove=SahlTheme.secondary(this,"Remove this folder",()->removePair(pair));
    remove.setTextColor(android.graphics.Color.parseColor("#E8857A"));actions.addView(remove);

   }
   if(all.length()>1){
    TextView empty=SahlTheme.label(this,"No matching folder backups.",15,SahlTheme.MUTED);
    pairs.addView(empty);
    Runnable filter=()->{
     String query=folderQuery.trim().toLowerCase(Locale.ROOT);
     boolean any=false;
     for(Map.Entry<View,String> item:searchable.entrySet()){
      boolean match=item.getValue().contains(query);
      item.getKey().setVisibility(match?View.VISIBLE:View.GONE);
      any|=match;
     }
     empty.setVisibility(any?View.GONE:View.VISIBLE);
    };
    search.addTextChangedListener(new android.text.TextWatcher(){
     public void beforeTextChanged(CharSequence text,int start,int count,int after){}
     public void onTextChanged(CharSequence text,int start,int before,int count){
      folderQuery=text.toString();
      filter.run();
     }
     public void afterTextChanged(android.text.Editable text){}
    });
    filter.run();
   }
  }catch(Exception e){message("Could not load folder backups.");}
  LinearLayout log=card("History");history=SahlTheme.label(this,"",15,SahlTheme.MUTED);refreshHistory();
  log.addView(SahlTheme.secondary(this,"Refresh history",this::refreshHistory));
  log.addView(SahlTheme.secondary(this,"View full history",()->{
   ScrollView list=new ScrollView(this);
   list.addView(SahlTheme.label(this,historyText(50),15,SahlTheme.TEXT));
   Tr.builder(this).setTitle("Backup history").setView(list).setPositiveButton("Close",null).show();
  }));
  log.addView(history);
  Tr.tree(this,content);
 }
 private void watchWork(String name,TextView label,boolean periodic,boolean timed){
  androidx.lifecycle.LiveData<List<WorkInfo>> live=timed?WorkManager.getInstance(this).getWorkInfosByTagLiveData(name)
   :WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(name);
  androidx.lifecycle.Observer<List<WorkInfo>> observer=rows->{
   if(isDestroyed())return;
   if(rows==null||rows.isEmpty()){label.setText(periodic?"Schedule: off":"Manual: ready");return;}
   WorkInfo selected=rows.get(0);
   for(WorkInfo row:rows)if(!row.getState().isFinished()){selected=row;if(row.getState()==WorkInfo.State.RUNNING)break;}
   if(timed&&selected.getState()!=WorkInfo.State.RUNNING){
    for(WorkInfo row:rows)if(row.getState()==WorkInfo.State.ENQUEUED
     &&(selected.getState()!=WorkInfo.State.ENQUEUED||row.getNextScheduleTimeMillis()<selected.getNextScheduleTimeMillis()))selected=row;
   }
   String prefix=timed?"Schedule: ":periodic?"Daily: ":"Manual: ";
   switch(selected.getState()){
    case RUNNING:
     Data progress=selected.getProgress();
     String file=Objects.toString(progress.getString("file"),"Preparing backup");
     long total=progress.getLong("totalBytes",0);
     String transfer="";
     if(total>0){
      long accepted=progress.getLong("acceptedBytes",0);
      String verb=progress.getBoolean("resumed",false)?"Resumed upload":"Uploading";
      transfer=String.format(Locale.getDefault(),"\n%s: %.1f / %.1f MiB (%d%%)",
       verb,accepted/1048576.0,total/1048576.0,accepted*100/total);
     }
     int uploaded=progress.getInt("uploaded",0),downloaded=progress.getInt("downloaded",0);
     String totals=progress.hasKeyWithValueOfType("filesChecked",Integer.class)
      ?"Files checked so far: "+progress.getInt("filesChecked",0)+" / Synced: "+(uploaded+downloaded)+"\n":"";
     label.setText(prefix+"Running - "+file+transfer+"\n"+totals+uploaded+" uploaded, "+downloaded
      +" downloaded, "+progress.getInt("unchanged",0)+" unchanged, "+progress.getInt("conflicts",0)+" conflicts");
     break;
    case ENQUEUED: label.setText(prefix+(timed?(selected.getNextScheduleTimeMillis()>System.currentTimeMillis()
      ?"Next: "+java.text.DateFormat.getDateTimeInstance().format(new Date(selected.getNextScheduleTimeMillis()))
      :"Due / waiting for Android and selected conditions"):periodic?"Scheduled / waiting for Android and selected conditions":"Queued / waiting for network, battery or another backup")+(selected.getRunAttemptCount()>0?" - retry pending":""));break;
    case SUCCEEDED: label.setText(prefix+selected.getOutputData().getString("summary"));break;
    case FAILED: label.setText(prefix+Objects.toString(selected.getOutputData().getString("summary"),
     "Failed or conflicts - see Backup activity; tap Back up now / Retry"));break;
    case CANCELLED: label.setText(prefix+"Cancelled / off. An accepted cloud request may still finish.");break;
    default: label.setText(prefix+"Waiting");
   }
  };
  live.observeForever(observer);unwatch.add(()->live.removeObserver(observer));
 }
 private void cancelBackup(JSONObject pair){
  Tr.builder(this).setTitle("Cancel backup?").setMessage("Stop queued and running backups for this folder and turn off its schedule. Completed files and history are kept. A request already accepted by OneDrive may finish.").setNegativeButton("Keep running",null).setPositiveButton("Cancel backup",(d,w)->{
   try{JSONObject saved=Store.pair(this,pair.getString("id"));synchronized(BackupSchedule.class){
    saved.put("daily",false).put("scheduleMode",0).put("scheduleGeneration","");
    Store.update(this,saved);BackupSchedule.cancel(this,pair.optString("id"));}
    WorkManager wm=WorkManager.getInstance(this);wm.cancelUniqueWork("manual-"+pair.optString("id"));
    Store.history(this,pair.optString("id"),pair.optString("name")+": cancellation requested; schedule turned off. Completed uploads and history kept.");draw();loadAccount();
   }catch(Exception e){message("Could not cancel backup. Try again.");}
  }).show();
 }
 private void editPair(JSONObject original){
  try{
   JSONObject pair=Store.pair(this,original.getString("id"));
   LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int pad=SahlTheme.dp(this,20);form.setPadding(pad,pad,pad,pad);
   form.addView(SahlTheme.label(this,"Settings apply to future runs. A running backup keeps its current settings.",14,SahlTheme.MUTED));
   EditText name=new EditText(this);name.setSingleLine(true);name.setHint("Backup name");name.setText(pair.getString("name"));form.addView(name);
   EditText exclude=new EditText(this);exclude.setHint("Exclusions, one pattern per line");exclude.setText(pair.optString("exclude"));form.addView(exclude);
   CheckBox wifi=new CheckBox(this);wifi.setText("Unmetered network only");wifi.setChecked(pair.optBoolean("unmetered",true));form.addView(wifi);
   CheckBox charging=new CheckBox(this);charging.setText("Only while charging");charging.setChecked(pair.optBoolean("charging"));form.addView(charging);
   form.addView(SahlTheme.label(this,"Folders",16,SahlTheme.TEXT));
   final AlertDialog[] holder=new AlertDialog[1];
   form.addView(SahlTheme.secondary(this,"Change phone folder",()->{
    editingPair=original.optString("id");if(holder[0]!=null)holder[0].dismiss();
    pickPhoneFolder(45,Uri.parse(pair.optString("local")));
   }));
   form.addView(SahlTheme.secondary(this,"Change OneDrive folder ("+pair.optString("remoteName")+")",()->{
    editingPair=original.optString("id");if(holder[0]!=null)holder[0].dismiss();
    selectAccount("Choose a OneDrive account",true,owner->chooseRemote("root","OneDrive",owner));
   }));
   Spinner direction=addDirection(form,pair.optString("direction","upload"));
   CheckBox deletions=new CheckBox(this);deletions.setText("Sync deletions (two-way). Deleted files stay in Version history.");
   deletions.setChecked(pair.optBoolean("syncDeletions",true));form.addView(deletions);
   form.addView(SahlTheme.label(this,"Older copies to keep per file",15,SahlTheme.TEXT));
   Spinner keep=new Spinner(this);
   int[] keepValues={3,10,30,100,0};
   keep.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,Tr.all(this,new String[]{"3","10","30","100","All (no cleanup)"})));
   for(int k=0;k<keepValues.length;k++)if(keepValues[k]==pair.optInt("keepVersions",10))keep.setSelection(k);
   form.addView(keep);
   CheckBox notify=new CheckBox(this);notify.setText("Notify me when this folder finishes syncing");
   notify.setChecked(pair.optBoolean("notify",true));form.addView(notify);
   ScheduleEditor schedule=new ScheduleEditor(this,form,pair);
   ScrollView scroll=new ScrollView(this);scroll.addView(form);
   AlertDialog dialog=Tr.builder(this).setTitle("Edit backup settings").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
   holder[0]=dialog;
   dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
    try{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a backup name");return;}
     if(!applyDirection(pair,direction))return;
     pair.put("name",title).put("exclude",exclude.getText().toString()).put("unmetered",wifi.isChecked()).put("charging",charging.isChecked())
      .put("syncDeletions",deletions.isChecked()).put("keepVersions",keepValues[keep.getSelectedItemPosition()]).put("notify",notify.isChecked());
     long due=schedule.apply(pair);
     synchronized(BackupSchedule.class){
      Store.update(this,pair);BackupSchedule.cancel(this,pair.getString("id"));
      if(due>0)BackupSchedule.enqueue(this,pair,due);
     }

     dialog.dismiss();draw();loadAccount();if(due>0)offerBackgroundSetup();
    }catch(IllegalArgumentException e){message(e.getMessage());}catch(Exception e){message("Could not save backup settings.");}
   }));dialog.show();
  }catch(Exception e){message("Could not load backup settings.");}
 }
 private void showFolderHistory(JSONObject pair){
  ScrollView list=new ScrollView(this);
  String entries=historyText("history-"+pair.optString("id"),50);
  list.addView(SahlTheme.label(this,entries,15,SahlTheme.TEXT));
  Tr.builder(this).setTitle(pair.optString("name")+" - Backup activity")
   .setView(list).setPositiveButton("Close",null).show();
 }
 private void refreshHistory(){history.setText(historyText(3));}
 private String historyText(int limit){return historyText("history",limit);}
 private String historyText(String key,int limit){
  try{String pairId="history".equals(key)?null:key.substring("history-".length());JSONArray all=Store.historyList(this,pairId,limit);StringBuilder text=new StringBuilder();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);text.append(java.text.DateFormat.getDateTimeInstance().format(new Date(row.getLong("time")))).append('\n').append(row.getString("message")).append("\n\n");}return text.length()==0?"No backup runs yet.":text.toString();}
  catch(Exception e){return "History is unavailable.";}
 }
 private void updateAccountButtons(){
  connectButton.setEnabled(auth!=null);
  connectButton.setText(!Auth.configured()?"OneDrive setup required":auth==null?"Preparing OneDrive...":"Add Microsoft account");
  disconnectButton.setEnabled(auth!=null&&!accounts.isEmpty());
  for(Map.Entry<TextView,JSONObject> entry:ownerLabels.entrySet())updateOwnerLabel(entry.getKey(),entry.getValue());
 }

 private void updateOwnerLabel(TextView label,JSONObject pair){
  IAccount owner=connectedAccount(pair.optString("account"));
  label.setText(owner==null?"Account: "+pair.optString("accountLabel","Microsoft account")+" (connect to access)"
   :"Account: "+accountLabel(owner));
 }

 private void connect(){
  if(auth==null)return;
  AuthenticationCallback callback=new AuthenticationCallback(){
   public void onSuccess(IAuthenticationResult result){loadAccount();}
   public void onError(MsalException error){message("Microsoft sign-in did not complete. Try adding the account again.");}
   public void onCancel(){}
  };
  auth.acquireToken(new AcquireTokenParameters.Builder().startAuthorizationFromActivity(this)
   .withScopes(Arrays.asList(Auth.SCOPES)).withPrompt(Prompt.SELECT_ACCOUNT).withCallback(callback).build());
 }

 private void disconnect(){
  if(auth==null)return;
  selectAccount("Disconnect which account?",false,owner->Tr.builder(this)
   .setTitle("Disconnect Microsoft account?")
   .setMessage("Disconnect "+accountLabel(owner)+"? Backups for this account will require reconnecting it. Your folder settings stay saved.")
   .setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(dialog,button)->io.execute(()->{
    try{
     synchronized(BackupWorker.class){
      if(!auth.removeAccount(owner))throw new java.io.IOException("Account was not removed");
     }
     runOnUiThread(this::loadAccount);
    }catch(Exception error){runOnUiThread(()->message("Could not disconnect this account. Try again."));}
   })).show());
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==43){saveSnapshotCopy(result,data);return;}
  if(request==44){completeWriteGrant(result,data);return;}
  if(request==45){
   String id=editingPair;editingPair=null;
   if(result!=RESULT_OK||data==null||data.getData()==null||id==null)return;
   try{
    takeFolderGrant(data);
    if(!FolderSync.writable(getContentResolver(),data.getData())){message("Allow write access when choosing the folder.");return;}
    JSONObject pair=Store.pair(this,id);
    changeFolders(pair.put("local",data.getData().toString()),"Phone folder changed");
   }catch(Exception e){message("Could not change the phone folder.");}
   return;
  }
  if(request==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){
   Uri uri=data.getData();try{takeFolderGrant(data);pendingLocal=uri.toString();selectAccount("Choose a OneDrive account",true,owner->chooseRemote("root","OneDrive",owner));}catch(Exception e){message("Persistent folder access was not granted.");}
  }
 }
 private void pickPhoneFolder(int request,Uri initial){
  Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
   .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  if(initial!=null)pick.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,initial);
  startActivityForResult(pick,request);
 }

 private void takeFolderGrant(Intent data){
  int flags=data.getFlags();
  if((flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)
   throw new SecurityException("Phone folder read permission was not granted");
  if((flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0){
   getContentResolver().takePersistableUriPermission(data.getData(),
    Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }else{
   getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }
 }

 private void completeWriteGrant(int result,Intent data){
  String tree=pendingWriteTree;
  pendingWriteTree=null;
  if(result!=RESULT_OK||data==null||data.getData()==null||tree==null)return;
  try{
   Uri original=Uri.parse(tree);
   if(!original.equals(data.getData())){message("Select the original phone folder to keep this backup pair.");return;}
   takeFolderGrant(data);
   if(FolderSync.writable(getContentResolver(),original))message("Write access granted. Save the sync mode in backup settings.");
   else message("Android did not grant write access to this folder.");
  }catch(Exception error){message("Could not grant write access to this folder.");}
 }

 private Spinner addDirection(LinearLayout form,String saved){
  form.addView(SahlTheme.label(this,"Sync mode",16,SahlTheme.TEXT));
  Spinner picker=new Spinner(this);
  picker.setContentDescription("Sync mode");
  ArrayAdapter<String> options=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,
   Tr.all(this,new String[]{"Upload only (phone to OneDrive)","Download only (OneDrive to phone)","Two-way (newest modified wins)"}));
  options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  picker.setAdapter(options);
  picker.setSelection("newest".equals(saved)?2:"download".equals(saved)?1:0);
  form.addView(picker);
  return picker;
 }

 private String directionLabel(JSONObject pair){
  switch(SyncDirection.from(pair.optString("direction"))){
   case DOWNLOAD:return "OneDrive to phone";
   case NEWEST:return "Two-way (newest wins)";
   default:return "Phone to OneDrive";
  }
 }

 private boolean applyDirection(JSONObject pair,Spinner direction)throws Exception{
  int selected=direction.getSelectedItemPosition();
  Uri tree=Uri.parse(pair.getString("local"));
  if(selected!=0&&!FolderSync.writable(getContentResolver(),tree)){
   Tr.builder(this).setTitle("Allow writing to the phone folder?")
    .setMessage("Select the same phone folder to allow Sahl Sync to save files from OneDrive.")
    .setNegativeButton("Cancel",null).setPositiveButton("Select folder",(dialog,button)->{
     pendingWriteTree=tree.toString();
     pickPhoneFolder(44,tree);
    }).show();
   return false;
  }
  pair.put("direction",selected==2?"newest":selected==1?"download":"upload");
  return true;
 }

 private void chooseRemote(String id,String name,IAccount owner){
  if(auth==null)return;status.setText("Loading OneDrive folders...");
  io.execute(()->{
   try{
    Graph graph=Auth.graph(auth,owner.getId());
    JSONObject parent=graph.json("GET","/me/drive/"+(id.equals("root")?"root":"items/"+Uri.encode(id)),null);
    JSONArray folders=graph.folders(parent.getString("id"));
    List<String> options=new ArrayList<>();options.add("Use this folder");options.add("Back to OneDrive root");for(int i=0;i<folders.length();i++)options.add(folders.getJSONObject(i).getString("name"));
    runOnUiThread(()->{loadAccount();Tr.builder(this).setTitle(name).setItems(options.toArray(new String[0]),(d,index)->{try{if(index==0&&editingPair!=null)changeRemote(parent.getString("id"),name,owner);else if(index==0)configurePair(parent.getString("id"),name,owner);else if(index==1)chooseRemote("root","OneDrive",owner);else{JSONObject folder=folders.getJSONObject(index-2);chooseRemote(folder.getString("id"),folder.getString("name"),owner);}}catch(Exception e){message("Could not select folder.");}}).setNegativeButton("Cancel",null).show();});
   }catch(Exception e){runOnUiThread(()->{loadAccount();message("Could not load OneDrive folders. Check connection and account permission.");});}
  });
 }
 private String editingPair;
 private void changeRemote(String remote,String remoteName,IAccount owner)throws Exception{
  String id=editingPair;editingPair=null;
  JSONObject pair=Store.pair(this,id).put("remote",remote).put("remoteName",remoteName)
   .put("account",owner.getId()).put("accountLabel",accountLabel(owner));
  changeFolders(pair,"OneDrive folder changed to "+remoteName);
 }
 /** Saves new folders and resets sync records, so the next run compares both folders fresh and deletes nothing. */
 private void changeFolders(JSONObject pair,String note)throws Exception{
  String id=pair.getString("id");
  synchronized(BackupSchedule.class){Store.update(this,pair);}
  WorkManager.getInstance(this).cancelUniqueWork("manual-"+id);
  Store.forgetAll(this,id);
  Store.history(this,id,note+". The next sync compares both folders fresh; nothing is deleted on that run.");
  message(note+". Tap Back up now to sync.");
  draw();loadAccount();
 }
 private void configurePair(String remote,String remoteName,IAccount owner){
  LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int pad=SahlTheme.dp(this,20);form.setPadding(pad,pad,pad,pad);
  EditText name=new EditText(this);name.setHint("Backup name");name.setSingleLine(true);form.addView(name);
  EditText exclude=new EditText(this);exclude.setHint("Exclusions, one pattern per line: *.tmp");form.addView(exclude);
  CheckBox wifi=new CheckBox(this);wifi.setText("Unmetered network only");wifi.setChecked(true);form.addView(wifi);
  CheckBox charging=new CheckBox(this);charging.setText("Only while charging");form.addView(charging);
  Spinner direction=addDirection(form,"upload");
  ScheduleEditor schedule=new ScheduleEditor(this,form,new JSONObject());
  ScrollView scroll=new ScrollView(this);scroll.addView(form);
  AlertDialog dialog=Tr.builder(this).setTitle("Create folder backup").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
  dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   try{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a backup name");return;}
    JSONObject pair=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",title).put("local",pendingLocal).put("remote",remote).put("remoteName",remoteName).put("account",owner.getId()).put("accountLabel",accountLabel(owner)).put("exclude",exclude.getText().toString()).put("unmetered",wifi.isChecked()).put("charging",charging.isChecked());
    if(!applyDirection(pair,direction))return;
    long due=schedule.apply(pair);
    synchronized(BackupSchedule.class){Store.add(this,pair);if(due>0)BackupSchedule.enqueue(this,pair,due);}dialog.dismiss();draw();loadAccount();if(due>0)offerBackgroundSetup();
   }catch(IllegalArgumentException e){message(e.getMessage());}catch(Exception e){message("Could not save folder backup.");}
  }));dialog.show();
 }
 private Graph graphForPair(JSONObject pair)throws Exception{
  if(auth==null)throw new java.io.IOException("Sign-in unavailable");
  return Auth.graph(auth,pair.getString("account"));
 }
 private void showVersionFiles(JSONObject pair){
  if(connectedAccount(pair.optString("account"))==null){message("Connect the Microsoft account for this folder first.");return;}
  try{
   JSONArray files=Store.files(this,pair.getString("id"));
   if(files.length()==0){message("Run this backup first. Version history stores the previous contents when a backed-up file changes.");return;}
   String[] names=new String[files.length()];for(int i=0;i<names.length;i++)names[i]=files.getJSONObject(i).getString("path");
   Tr.builder(this).setTitle("Version history - choose file").setItems(names,(d,index)->{try{showVersions(pair,files.getJSONObject(index));}catch(Exception e){message("Could not read file history.");}}).setNegativeButton("Close",null).show();
  }catch(Exception e){message("Could not read file history.");}
 }
 private void showVersions(JSONObject pair,JSONObject file){
  io.execute(()->{
   try{
    JSONArray versions=OneDriveBackup.versions(graphForPair(pair),pair,file.getString("path"));
    runOnUiThread(()->{
     if(isFinishing()||isDestroyed())return;
     if(versions.length()==0){message("No older copies yet. On the next change, Sahl Sync archives the current file before updating it. Old hash-named alpha copies are unchanged.");return;}
     try{
      String[] labels=new String[versions.length()];
      for(int i=0;i<labels.length;i++){JSONObject version=versions.getJSONObject(i);String filename=version.getString("name");long date=Long.parseLong(filename.substring(0,filename.indexOf('-')));labels[i]=java.text.DateFormat.getDateTimeInstance().format(new Date(date))+" - "+version.optLong("size")+" bytes";}
      Tr.builder(this).setTitle(file.getString("name")+" - older copies").setItems(labels,(d,index)->{
       try{JSONObject version=versions.getJSONObject(index);Tr.builder(this).setTitle("Save older version?").setMessage("Save this version as a separate file on your phone. Your current file and cloud backup stay unchanged.").setNegativeButton("Cancel",null).setPositiveButton("Save a copy",(dialog,which)->downloadSnapshot(pair,file,version)).show();}catch(Exception e){message("Could not select version.");}
      }).setNegativeButton("Close",null).show();
     }catch(Exception e){message("Could not display versions.");}
    });
   }catch(Exception e){runOnUiThread(()->message("Could not load version history. Check your connection and account."));}
  });
 }
 private void downloadSnapshot(JSONObject pair,JSONObject file,JSONObject snapshot){
  io.execute(()->{
   java.io.File cached=null;
   try{
    cached=java.io.File.createTempFile("restore-",".tmp",getCacheDir());
    String hash=graphForPair(pair).download(snapshot.getString("id"),cached);
    if(!hash.equals(VersionedBackup.snapshotHash(snapshot.getString("name"))))throw new java.io.IOException("Stored version checksum mismatch");
    java.io.File ready=cached;
    runOnUiThread(()->{
     if(isFinishing()||isDestroyed()){ready.delete();return;}
     if(pendingSnapshot!=null)pendingSnapshot.delete();pendingSnapshot=ready;pendingSnapshotName=file.optString("name","restored-file");
     startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"restored-"+pendingSnapshotName),43);
    });cached=null;
   }catch(Exception e){runOnUiThread(()->message("Could not verify or download the archived version. No file was replaced."));}
   finally{if(cached!=null)cached.delete();}
  });
 }
 private void saveSnapshotCopy(int result,Intent data){
  java.io.File source=pendingSnapshot;pendingSnapshot=null;
  if(source==null)return;
  if(result!=RESULT_OK||data==null||data.getData()==null){source.delete();return;}
  Uri destination=data.getData();
  io.execute(()->{
   try(java.io.InputStream in=new java.io.FileInputStream(source);java.io.OutputStream out=getContentResolver().openOutputStream(destination,"wt")){
    if(out==null)throw new java.io.IOException("Cannot write restored copy");
    byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)out.write(block,0,n);
    runOnUiThread(()->message("Older version saved as a copy. Your current file is unchanged."));
   }catch(Exception e){runOnUiThread(()->message("Could not save the copy. The selected destination may be incomplete; choose another destination and try again."));}
   finally{source.delete();}
  });
 }
 private void removePair(JSONObject pair){
  Tr.builder(this).setTitle("Remove this folder?")
   .setMessage("Sahl Sync stops syncing it. Your files on the phone and in OneDrive are not deleted.")
   .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{
    try{
     String id=pair.getString("id");
     synchronized(BackupSchedule.class){BackupSchedule.cancel(this,id);Store.remove(this,id);}
     WorkManager.getInstance(this).cancelUniqueWork("manual-"+id);
     draw();loadAccount();
    }catch(Exception e){message("Could not remove the folder.");}
   }).show();
 }
 private void chooseLanguage(){
  String[] modes={"auto","en","ar"};
  String[] labels={"Automatic (phone language)","English","العربية"};
  int current=java.util.Arrays.asList(modes).indexOf(Tr.language(this));
  Tr.builder(this).setTitle("Language").setSingleChoiceItems(Tr.all(this,labels),Math.max(0,current),(d,which)->{
   Tr.setLanguage(this,modes[which]);d.dismiss();draw();loadAccount();
  }).setNegativeButton("Cancel",null).show();
 }
 private void message(String text){if(!isFinishing()&&!isDestroyed())Tr.builder(this).setMessage(text).setPositiveButton("OK",null).show();}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("local",pendingLocal);out.putString("writePair",pendingWriteTree);if(pendingSnapshot!=null)out.putString("snapshot",pendingSnapshot.getAbsolutePath());out.putString("snapshotName",pendingSnapshotName);}
 @Override protected void onDestroy(){for(Runnable remove:unwatch)remove.run();unwatch.clear();Store.prefs(this).unregisterOnSharedPreferenceChangeListener(historyListener);io.shutdown();super.onDestroy();}
}
