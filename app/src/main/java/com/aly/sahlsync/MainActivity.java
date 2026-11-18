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
 private ISingleAccountPublicClientApplication auth;
 private IAccount account;
 private LinearLayout content;
 private TextView status,history;
 private Button connectButton,disconnectButton;
 private final List<Runnable> unwatch=new ArrayList<>();
 private final android.content.SharedPreferences.OnSharedPreferenceChangeListener historyListener=(prefs,key)->{if("history".equals(key))runOnUiThread(this::refreshHistory);};
 private String pendingLocal;
 private String folderQuery="";
 private String openFolder;
 private java.io.File pendingSnapshot;
 private String pendingSnapshotName;
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);if(saved!=null)pendingLocal=saved.getString("local");
  if(saved!=null){String cached=saved.getString("snapshot");if(cached!=null){java.io.File file=new java.io.File(cached);try{if(file.getCanonicalFile().getParentFile().equals(getCacheDir().getCanonicalFile()))pendingSnapshot=file;}catch(java.io.IOException ignored){}}pendingSnapshotName=saved.getString("snapshotName");}
  Store.prefs(this).registerOnSharedPreferenceChangeListener(historyListener);
  draw();
  if(Auth.configured())io.execute(()->{try{ISingleAccountPublicClientApplication app=Auth.open(getApplicationContext());runOnUiThread(()->{auth=app;loadAccount();});}catch(Exception e){runOnUiThread(()->{status.setText("Could not initialize Microsoft sign-in. Reopen the app to retry.");});}});
 }
 @Override protected void onResume(){super.onResume();if(auth!=null)loadAccount();}
 private void loadAccount(){auth.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback(){
  public void onAccountLoaded(IAccount a){account=a;status.setText(a==null?"Not connected to OneDrive":"Connected: "+a.getUsername());updateAccountButtons();}
  public void onAccountChanged(IAccount prior,IAccount current){account=current;updateAccountButtons();}
  public void onError(MsalException e){status.setText("Sign-in needs attention. Connect again.");account=null;updateAccountButtons();}
 });}
 private LinearLayout card(String title){
  LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);int p=SahlTheme.dp(this,18);card.setPadding(p,p,p,p);card.setBackground(SahlTheme.rounded(this,SahlTheme.PANEL));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=SahlTheme.dp(this,16);content.addView(card,lp);
  card.addView(SahlTheme.label(this,title,21,SahlTheme.TEXT));return card;
 }
 private void draw(){
  for(Runnable remove:unwatch)remove.run();unwatch.clear();
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
  scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{SahlTheme.TOP,SahlTheme.BOTTOM}));
  scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setFocusableInTouchMode(true);int p=SahlTheme.dp(this,20);content.setPadding(p,p,p,p);scroll.addView(content);setContentView(scroll);content.requestFocus();
  content.addView(SahlTheme.label(this,"SAHL  /  SYNC",14,SahlTheme.GOLD));
  content.addView(SahlTheme.label(this,"Your folders. Backed up.",30,SahlTheme.TEXT));
  content.addView(SahlTheme.label(this,"OneDrive backup - Early preview",15,SahlTheme.MUTED));
  LinearLayout connect=card("Microsoft account");status=SahlTheme.label(this,Auth.configured()?"Loading account...":"Microsoft sign-in setup is pending for this build.",16,SahlTheme.MUTED);connect.addView(status);
  connectButton=SahlTheme.button(this,"Connect OneDrive",this::connect);connect.addView(connectButton);
  disconnectButton=SahlTheme.button(this,"Disconnect",this::disconnect);connect.addView(disconnectButton);updateAccountButtons();
  LinearLayout pairs=card("Folder backups");
  pairs.addView(SahlTheme.label(this,"Upload-only. One current file keeps its original name. Before an update, Sahl Sync stores a verified older copy in its history folder. No automatic history deletion. Alpha limit: 20 MiB per file and 5,000 items per run.",15,SahlTheme.MUTED));
  pairs.addView(SahlTheme.button(this,"Add folder backup",()->{
   if(account==null){message("Connect OneDrive first.");return;}
   startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),42);
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
    entry.setPadding(0,SahlTheme.dp(this,12),0,0);
    pairs.addView(entry);
    searchable.put(entry,(title+" "+pair.getString("remoteName")).toLowerCase(Locale.ROOT));
    LinearLayout actions=new LinearLayout(this);
    actions.setOrientation(LinearLayout.VERTICAL);
    actions.setVisibility(id.equals(openFolder)?View.VISIBLE:View.GONE);
    Button heading=SahlTheme.button(this,"",()->{});
    heading.setAllCaps(false);
    heading.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
    heading.setText(title+" / "+(id.equals(openFolder)?"Hide controls":"Show controls"));
    heading.setOnClickListener(view->{
     boolean expanded=actions.getVisibility()!=View.VISIBLE;
     for(View other:searchable.keySet()){
      if(other!=entry){
       LinearLayout row=(LinearLayout)other;
       row.getChildAt(row.getChildCount()-1).setVisibility(View.GONE);
       Button otherHeading=(Button)row.getChildAt(0);
       otherHeading.setText(otherHeading.getText().toString().replaceFirst(" / Hide controls$"," / Show controls"));
      }
     }
     openFolder=expanded?id:null;
     actions.setVisibility(expanded?View.VISIBLE:View.GONE);
     heading.setText(title+" / "+(expanded?"Hide controls":"Show controls"));
    });
    entry.addView(heading);
    entry.addView(SahlTheme.label(this,"Phone to "+pair.getString("remoteName")+"\n"+(pair.optBoolean("unmetered")?"Unmetered network":"Any network")+(pair.optBoolean("charging")?" / Charging":"")+(pair.optBoolean("daily")?" / Daily":" / Manual"),14,SahlTheme.MUTED));
    TextView manual=SahlTheme.label(this,"",14,SahlTheme.MUTED),scheduled=SahlTheme.label(this,"",14,SahlTheme.MUTED);
    entry.addView(manual);entry.addView(scheduled);entry.addView(actions);
    Button backup=SahlTheme.button(this,"Back up now / Retry",()->BackupWorker.enqueue(this,pair,false));actions.addView(backup);
    Button cancel=SahlTheme.button(this,"Cancel current backup",()->cancelBackup(pair));actions.addView(cancel);
    watchWork("manual-"+pair.optString("id"),manual,false);
    watchWork("scheduled-"+pair.optString("id"),scheduled,true);
    actions.addView(SahlTheme.button(this,"Edit backup settings",()->editPair(pair)));
    actions.addView(SahlTheme.button(this,"Backup activity",()->showFolderHistory(pair)));
    actions.addView(SahlTheme.button(this,"Version history",()->showVersionFiles(pair)));

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
  log.addView(SahlTheme.button(this,"Refresh history",this::refreshHistory));
  log.addView(SahlTheme.button(this,"View full history",()->{
   ScrollView list=new ScrollView(this);
   list.addView(SahlTheme.label(this,historyText(50),15,SahlTheme.TEXT));
   new AlertDialog.Builder(this).setTitle("Backup history").setView(list).setPositiveButton("Close",null).show();
  }));
  log.addView(history);
 }
 private void watchWork(String name,TextView label,boolean periodic){
  androidx.lifecycle.LiveData<List<WorkInfo>> live=WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(name);
  androidx.lifecycle.Observer<List<WorkInfo>> observer=rows->{
   if(isDestroyed())return;
   if(rows==null||rows.isEmpty()){label.setText(periodic?"Schedule: off":"Manual: ready");return;}
   WorkInfo selected=rows.get(0);
   for(WorkInfo row:rows)if(!row.getState().isFinished()){selected=row;if(row.getState()==WorkInfo.State.RUNNING)break;}
   String prefix=periodic?"Daily: ":"Manual: ";
   switch(selected.getState()){
    case RUNNING: Data progress=selected.getProgress();label.setText(prefix+"Running - "+progress.getString("file")+"\n"+progress.getInt("uploaded",0)+" uploaded, "+progress.getInt("unchanged",0)+" unchanged, "+progress.getInt("conflicts",0)+" conflicts");break;
    case ENQUEUED: label.setText(prefix+(periodic?"Scheduled / waiting for Android and selected conditions":"Queued / waiting for network, battery or another backup")+(selected.getRunAttemptCount()>0?" - retry pending":""));break;
    case SUCCEEDED: label.setText(prefix+selected.getOutputData().getString("summary"));break;
    case FAILED: label.setText(prefix+"Failed or conflicts - see Backup activity; tap Back up now / Retry");break;
    case CANCELLED: label.setText(prefix+"Cancelled / off. An accepted cloud request may still finish.");break;
    default: label.setText(prefix+"Waiting");
   }
  };
  live.observeForever(observer);unwatch.add(()->live.removeObserver(observer));
 }
 private void cancelBackup(JSONObject pair){
  new AlertDialog.Builder(this).setTitle("Cancel backup?").setMessage("Stop queued and running backups for this folder and turn off its daily schedule. Completed files and history are kept. A request already accepted by OneDrive may finish.").setNegativeButton("Keep running",null).setPositiveButton("Cancel backup",(d,w)->{
   try{JSONObject saved=Store.pair(this,pair.getString("id"));saved.put("daily",false);Store.update(this,saved);
    WorkManager wm=WorkManager.getInstance(this);wm.cancelUniqueWork("manual-"+pair.optString("id"));wm.cancelUniqueWork("scheduled-"+pair.optString("id"));
    Store.history(this,pair.optString("id"),pair.optString("name")+": cancellation requested; daily schedule turned off. Completed uploads and history kept.");draw();loadAccount();
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
   CheckBox daily=new CheckBox(this);daily.setText("Back up daily (approximate)");daily.setChecked(pair.optBoolean("daily"));form.addView(daily);
   ScrollView scroll=new ScrollView(this);scroll.addView(form);
   AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit backup settings").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
   dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
    try{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a backup name");return;}
     pair.put("name",title).put("exclude",exclude.getText().toString()).put("unmetered",wifi.isChecked()).put("charging",charging.isChecked()).put("daily",daily.isChecked());Store.update(this,pair);
     if(daily.isChecked())BackupWorker.enqueue(this,pair,true);else WorkManager.getInstance(this).cancelUniqueWork("scheduled-"+pair.getString("id"));
     dialog.dismiss();draw();loadAccount();
    }catch(Exception e){message("Could not save backup settings.");}
   }));dialog.show();
  }catch(Exception e){message("Could not load backup settings.");}
 }
 private void showFolderHistory(JSONObject pair){
  ScrollView list=new ScrollView(this);
  String entries=historyText("history-"+pair.optString("id"),50);
  list.addView(SahlTheme.label(this,entries,15,SahlTheme.TEXT));
  new AlertDialog.Builder(this).setTitle(pair.optString("name")+" - Backup activity")
   .setView(list).setPositiveButton("Close",null).show();
 }
 private void refreshHistory(){history.setText(historyText(3));}
 private String historyText(int limit){return historyText("history",limit);}
 private String historyText(String key,int limit){
  try{JSONArray all=new JSONArray(Store.prefs(this).getString(key,"[]"));StringBuilder text=new StringBuilder();for(int i=0;i<Math.min(limit,all.length());i++){JSONObject row=all.getJSONObject(i);text.append(java.text.DateFormat.getDateTimeInstance().format(new Date(row.getLong("time")))).append('\n').append(row.getString("message")).append("\n\n");}return text.length()==0?"No backup runs yet.":text.toString();}
  catch(Exception e){return "History is unavailable.";}
 }
 private void updateAccountButtons(){
  if(connectButton==null)return;
  connectButton.setEnabled(auth!=null&&account==null);connectButton.setText(!Auth.configured()?"OneDrive setup required":auth==null?"Preparing OneDrive...":account==null?"Connect OneDrive":"Connected");
  disconnectButton.setEnabled(auth!=null&&account!=null);
 }
 private void connect(){
  if(!Auth.configured()){message("This early build needs its Microsoft app registration configured before sign-in can work. No Microsoft password is collected by Sahl Sync.");return;}
  if(auth==null){message("Sign-in is still loading.");return;}
  AuthenticationCallback callback=new AuthenticationCallback(){public void onSuccess(IAuthenticationResult r){account=r.getAccount();loadAccount();}public void onError(MsalException e){message("Microsoft sign-in did not complete. Retry Connect OneDrive.");}public void onCancel(){message("Sign-in cancelled.");}};
  auth.acquireToken(new AcquireTokenParameters.Builder().startAuthorizationFromActivity(this).withScopes(Arrays.asList(Auth.SCOPES)).withCallback(callback).build());
 }
 private void disconnect(){
  if(auth==null)return;
  new AlertDialog.Builder(this).setTitle("Disconnect OneDrive?").setMessage("Stop queued backups and remove the saved Microsoft session. Existing files remain in OneDrive.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(d,w)->{
   WorkManager.getInstance(this).cancelAllWork();
   io.execute(()->{try{synchronized(BackupWorker.class){auth.signOut();}runOnUiThread(()->{account=null;loadAccount();});}catch(Exception e){runOnUiThread(()->message("Could not disconnect. Try again."));}});
  }).show();
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==43){saveSnapshotCopy(result,data);return;}
  if(request==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){
   Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);pendingLocal=uri.toString();chooseRemote("root","OneDrive");}catch(Exception e){message("Persistent folder access was not granted.");}
  }
 }
 private void chooseRemote(String id,String name){
  if(auth==null||account==null)return;status.setText("Loading OneDrive folders...");
  io.execute(()->{
   try{
    IAuthenticationResult token=Auth.token(auth);Graph graph=new Graph(token.getAccessToken());
    JSONObject parent=graph.json("GET","/me/drive/"+(id.equals("root")?"root":"items/"+Uri.encode(id)),null);
    JSONArray folders=graph.folders(parent.getString("id"));
    List<String> options=new ArrayList<>();options.add("Use this folder");options.add("Back to OneDrive root");for(int i=0;i<folders.length();i++)options.add(folders.getJSONObject(i).getString("name"));
    runOnUiThread(()->{loadAccount();new AlertDialog.Builder(this).setTitle(name).setItems(options.toArray(new String[0]),(d,index)->{try{if(index==0)configurePair(parent.getString("id"),name,token.getAccount().getId());else if(index==1)chooseRemote("root","OneDrive");else{JSONObject folder=folders.getJSONObject(index-2);chooseRemote(folder.getString("id"),folder.getString("name"));}}catch(Exception e){message("Could not select folder.");}}).setNegativeButton("Cancel",null).show();});
   }catch(Exception e){runOnUiThread(()->{loadAccount();message("Could not load OneDrive folders. Check connection and account permission.");});}
  });
 }
 private void configurePair(String remote,String remoteName,String owner){
  LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int pad=SahlTheme.dp(this,20);form.setPadding(pad,pad,pad,pad);
  EditText name=new EditText(this);name.setHint("Backup name");name.setSingleLine(true);form.addView(name);
  EditText exclude=new EditText(this);exclude.setHint("Exclusions, one pattern per line: *.tmp");form.addView(exclude);
  CheckBox wifi=new CheckBox(this);wifi.setText("Unmetered network only");wifi.setChecked(true);form.addView(wifi);
  CheckBox charging=new CheckBox(this);charging.setText("Only while charging");form.addView(charging);
  CheckBox daily=new CheckBox(this);daily.setText("Back up daily (approximate)");form.addView(daily);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Create folder backup").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
  dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   try{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Enter a backup name");return;}
    JSONObject pair=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",title).put("local",pendingLocal).put("remote",remote).put("remoteName",remoteName).put("account",owner).put("exclude",exclude.getText()).put("unmetered",wifi.isChecked()).put("charging",charging.isChecked()).put("daily",daily.isChecked());
    Store.add(this,pair);if(daily.isChecked())BackupWorker.enqueue(this,pair,true);dialog.dismiss();draw();loadAccount();
   }catch(Exception e){message("Could not save folder backup.");}
  }));dialog.show();
 }
 private Graph graphForPair(JSONObject pair)throws Exception{
  if(auth==null)throw new java.io.IOException("Sign-in unavailable");
  IAuthenticationResult token=Auth.token(auth);
  if(!token.getAccount().getId().equals(pair.getString("account")))throw new java.io.IOException("Account changed");
  return new Graph(token.getAccessToken());
 }
 private void showVersionFiles(JSONObject pair){
  if(account==null){message("Connect OneDrive first.");return;}
  try{
   JSONArray files=Store.files(this,pair.getString("id"));
   if(files.length()==0){message("Run this backup first. Version history stores the previous contents when a backed-up file changes.");return;}
   String[] names=new String[files.length()];for(int i=0;i<names.length;i++)names[i]=files.getJSONObject(i).getString("path");
   new AlertDialog.Builder(this).setTitle("Version history - choose file").setItems(names,(d,index)->{try{showVersions(pair,files.getJSONObject(index));}catch(Exception e){message("Could not read file history.");}}).setNegativeButton("Close",null).show();
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
      new AlertDialog.Builder(this).setTitle(file.getString("name")+" - older copies").setItems(labels,(d,index)->{
       try{JSONObject version=versions.getJSONObject(index);new AlertDialog.Builder(this).setTitle("Save older version?").setMessage("Save this version as a separate file on your phone. Your current file and cloud backup stay unchanged.").setNegativeButton("Cancel",null).setPositiveButton("Save a copy",(dialog,which)->downloadSnapshot(pair,file,version)).show();}catch(Exception e){message("Could not select version.");}
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
 private void message(String text){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK",null).show();}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("local",pendingLocal);if(pendingSnapshot!=null)out.putString("snapshot",pendingSnapshot.getAbsolutePath());out.putString("snapshotName",pendingSnapshotName);}
 @Override protected void onDestroy(){for(Runnable remove:unwatch)remove.run();unwatch.clear();Store.prefs(this).unregisterOnSharedPreferenceChangeListener(historyListener);io.shutdown();super.onDestroy();}
}
