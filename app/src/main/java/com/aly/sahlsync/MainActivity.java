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
 private List<GoogleAuth.Account> googleAccounts=Collections.emptyList();
 private final Map<TextView,JSONObject> ownerLabels=new LinkedHashMap<>();
 private LinearLayout content,pairsList,accountsList,tabBar;
 private final ScrollView[] tabPages=new ScrollView[4];
 private int activeTab=0;
 private TextView status,history,lanSummary,settingsStatus;
 private Button connectButton,connectGoogleButton,disconnectButton;
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
  super.onCreate(saved);if(saved!=null){pendingLocal=saved.getString("local");pendingWriteTree=saved.getString("writePair");activeTab=saved.getInt("activeTab",0);}
  if(saved!=null){String cached=saved.getString("snapshot");if(cached!=null){java.io.File file=new java.io.File(cached);try{if(file.getCanonicalFile().getParentFile().equals(getCacheDir().getCanonicalFile()))pendingSnapshot=file;}catch(java.io.IOException ignored){}}pendingSnapshotName=saved.getString("snapshotName");}
  Store.prefs(this).registerOnSharedPreferenceChangeListener(historyListener);
  googleAccounts=GoogleAuth.accounts(this);
  draw();
  if(saved==null){
   if(!Store.prefs(this).getBoolean("setupSeen",false))showBackgroundSetup();
   else offerBackgroundSetupForExistingSchedules();
  }
  if(Auth.configured())io.execute(()->{try{IMultipleAccountPublicClientApplication app=Auth.open(getApplicationContext());runOnUiThread(()->{auth=app;loadAccount();});}catch(Exception e){runOnUiThread(()->{updateAccountStatus();});}});
 }
 @Override protected void onResume(){super.onResume();BackupNotifications.refreshStatus(this);googleAccounts=GoogleAuth.accounts(this);refreshAccountsTab();refreshSettingsStatus();if(auth!=null)loadAccount();
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

 private static final class CloudAccount {
  final SyncProvider provider;
  final String id,label;
  final IAccount msal;
  CloudAccount(SyncProvider provider,String id,String label,IAccount msal){
   this.provider=provider;this.id=id;this.label=label;this.msal=msal;
  }
 }

 private void loadAccount(){
  googleAccounts=GoogleAuth.accounts(this);
  IMultipleAccountPublicClientApplication session=auth;
  if(session==null){refreshAccountsTab();return;}
  io.execute(()->{
   try{
    List<IAccount> loaded=session.getAccounts();
    runOnUiThread(()->{
     if(isDestroyed()||isFinishing())return;
     accounts=new ArrayList<>(loaded);
     refreshAccountsTab();
    });
   }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())updateAccountStatus();});}
  });
 }

 private void updateAccountStatus(){
  if(status==null)return;
  int ms=accounts.size(),gd=googleAccounts.size();
  if(ms==0&&gd==0){
   if(!Auth.configured()&&!GoogleAuth.configured())status.setText("Cloud sign-in setup is pending for this build.");
   else status.setText("No cloud accounts connected");
   return;
  }
  List<String> parts=new ArrayList<>();
  if(ms>0)parts.add(ms+" Microsoft account"+(ms==1?"":"s"));
  if(gd>0)parts.add(gd+" Google Drive account"+(gd==1?"":"s"));
  status.setText("Connected: "+String.join(", ",parts));
 }

 private IAccount connectedAccount(String id){
  for(IAccount candidate:accounts)if(id.equals(candidate.getId()))return candidate;
  return null;
 }

 private String accountLabel(IAccount account){
  String username=account.getUsername();
  return username==null||username.isEmpty()?"Microsoft account":username;
 }

 private List<CloudAccount> allCloudAccounts(){
  List<CloudAccount> list=new ArrayList<>();
  for(IAccount a:accounts)list.add(new CloudAccount(SyncProvider.ONEDRIVE,a.getId(),accountLabel(a),a));
  for(GoogleAuth.Account g:googleAccounts)list.add(new CloudAccount(SyncProvider.GOOGLE_DRIVE,g.id,g.email,null));
  return list;
 }

 private void selectCloudAccount(String title,boolean autoSelect,java.util.function.Consumer<CloudAccount> action){
  List<CloudAccount> choices=allCloudAccounts();
  if(choices.isEmpty()){message("Connect OneDrive or Google Drive first.");return;}
  if(autoSelect&&choices.size()==1){action.accept(choices.get(0));return;}
  String[] labels=new String[choices.size()];
  for(int i=0;i<labels.length;i++){
   CloudAccount c=choices.get(i);
   labels[i]="["+c.provider.displayName+"] "+c.label;
  }
  Tr.builder(this).setTitle(title).setItems(labels,(dialog,index)->action.accept(choices.get(index)))
   .setNegativeButton("Cancel",null).show();
 }
 private LinearLayout card(LinearLayout parent,String title){
  LinearLayout card=SahlTheme.card(this);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=SahlTheme.dp(this,14);parent.addView(card,lp);
  card.addView(SahlTheme.label(this,title,20,SahlTheme.TEXT));
  return card;
 }

 private ScrollView makeTabPage(LinearLayout container){
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
  scroll.addView(container);
  return scroll;
 }

 private LinearLayout makeTabColumn(){
  LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);col.setFocusableInTouchMode(true);
  int p=SahlTheme.dp(this,16);col.setPadding(p,p,p,p);
  return col;
 }

 private void draw(){
  ownerLabels.clear();
  for(Runnable remove:unwatch)remove.run();unwatch.clear();

  LinearLayout root=new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL);
  root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{SahlTheme.TOP,SahlTheme.BOTTOM}));
  root.setOnApplyWindowInsetsListener((v,insets)->{
   v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
   return insets;
  });

  LinearLayout header=new LinearLayout(this);
  header.setOrientation(LinearLayout.VERTICAL);
  int hp=SahlTheme.dp(this,18);
  header.setPadding(hp,SahlTheme.dp(this,12),hp,SahlTheme.dp(this,6));
  header.addView(SahlTheme.label(this,"Sahl Sync",26,SahlTheme.TEXT));
  header.addView(SahlTheme.label(this,"Keep your phone, cloud drives and PC in sync.",14,SahlTheme.MUTED));
  root.addView(header);

  FrameLayout pager=new FrameLayout(this);
  LinearLayout.LayoutParams pagerLp=new LinearLayout.LayoutParams(-1,0,1f);
  root.addView(pager,pagerLp);

  // Tab 0: Syncs
  LinearLayout syncsCol=makeTabColumn();
  tabPages[0]=makeTabPage(syncsCol);
  pager.addView(tabPages[0]);

  LinearLayout pairsCard=card(syncsCol,"Folder backups");
  pairsCard.addView(SahlTheme.label(this,"Tap a folder to sync it or change its settings. Replaced and deleted files are kept in Version history.",14,SahlTheme.MUTED));
  pairsCard.addView(SahlTheme.button(this,"Add folder backup",()->{
   if(allCloudAccounts().isEmpty()){message("Connect OneDrive or Google Drive first.");selectTab(1);return;}
   pickPhoneFolder(42,null);
  }));
  EditText search=new EditText(this);
  search.setSingleLine(true);
  search.setHint(Tr.t(this,"Search folder backups"));
  search.setText(folderQuery);
  search.setTextColor(SahlTheme.TEXT);
  search.setHintTextColor(SahlTheme.MUTED);
  pairsCard.addView(search);
  pairsList=new LinearLayout(this);
  pairsList.setOrientation(LinearLayout.VERTICAL);
  pairsCard.addView(pairsList);
  search.addTextChangedListener(new android.text.TextWatcher(){
   public void beforeTextChanged(CharSequence text,int start,int count,int after){}
   public void onTextChanged(CharSequence text,int start,int before,int count){
    folderQuery=text.toString();
    filterPairs();
   }
   public void afterTextChanged(android.text.Editable text){}
  });
  syncsCol.addView(SahlTheme.secondary(this,"Sync with my PC (Wi-Fi)",()->startActivity(new Intent(this,LanActivity.class))));
  refreshPairs(search);

  // Tab 1: Accounts & Devices
  LinearLayout accountsCol=makeTabColumn();
  tabPages[1]=makeTabPage(accountsCol);
  pager.addView(tabPages[1]);

  LinearLayout connect=card(accountsCol,"Cloud accounts");
  status=SahlTheme.label(this,"",15,SahlTheme.MUTED);connect.addView(status);
  accountsList=new LinearLayout(this);accountsList.setOrientation(LinearLayout.VERTICAL);
  connect.addView(accountsList);
  connectButton=SahlTheme.button(this,"Connect OneDrive",this::connect);connect.addView(connectButton);
  connectGoogleButton=SahlTheme.button(this,"Connect Google Drive",this::connectGoogle);connect.addView(connectGoogleButton);
  disconnectButton=SahlTheme.secondary(this,"Disconnect an account",this::disconnect);connect.addView(disconnectButton);

  LinearLayout lanCard=card(accountsCol,"Paired PCs (Wi-Fi)");
  lanSummary=SahlTheme.label(this,"",14,SahlTheme.MUTED);lanCard.addView(lanSummary);
  lanCard.addView(SahlTheme.secondary(this,"Open PC Wi-Fi sync",()->startActivity(new Intent(this,LanActivity.class))));
  refreshAccountsTab();

  // Tab 2: History & Backups
  LinearLayout historyCol=makeTabColumn();
  tabPages[2]=makeTabPage(historyCol);
  pager.addView(tabPages[2]);

  LinearLayout phoneDataCard=card(historyCol,"Messages & apps");
  phoneDataCard.addView(SahlTheme.label(this,"Back up or restore SMS, call logs, and installed app lists.",14,SahlTheme.MUTED));
  phoneDataCard.addView(SahlTheme.secondary(this,"Open messages & apps backup",()->startActivity(new Intent(this,PhoneDataActivity.class))));

  LinearLayout log=card(historyCol,"History");
  history=SahlTheme.label(this,"",14,SahlTheme.MUTED);refreshHistory();
  log.addView(SahlTheme.secondary(this,"Refresh history",this::refreshHistory));
  log.addView(SahlTheme.secondary(this,"View full history",()->{
   ScrollView list=new ScrollView(this);
   list.addView(SahlTheme.label(this,historyText(50),15,SahlTheme.TEXT));
   Tr.builder(this).setTitle("Backup history").setView(list).setPositiveButton("Close",null).show();
  }));
  log.addView(history);

  // Tab 3: Settings
  LinearLayout settingsCol=makeTabColumn();
  tabPages[3]=makeTabPage(settingsCol);
  pager.addView(tabPages[3]);

  LinearLayout prefsCard=card(settingsCol,"Settings");
  settingsStatus=SahlTheme.label(this,"",14,SahlTheme.MUTED);prefsCard.addView(settingsStatus);
  prefsCard.addView(SahlTheme.secondary(this,"Background & notifications",this::showBackgroundSetup));
  prefsCard.addView(SahlTheme.secondary(this,"Language / اللغة",this::chooseLanguage));
  LinearLayout updateCard=card(settingsCol,"Version & updates");
  updateCard.addView(SahlTheme.label(this,"Installed version: v"+BuildConfig.VERSION_NAME,14,SahlTheme.MUTED));
  Button updates=SahlTheme.secondary(this,"Check for updates",()->{});
  updates.setOnClickListener(view->checkForUpdates(updates));
  updateCard.addView(updates);
  refreshSettingsStatus();

  // Bottom tab bar
  tabBar=new LinearLayout(this);
  tabBar.setOrientation(LinearLayout.HORIZONTAL);
  tabBar.setBackgroundColor(SahlTheme.PANEL);
  int tp=SahlTheme.dp(this,6);
  tabBar.setPadding(tp,tp,tp,tp);
  tabBar.setElevation(SahlTheme.dp(this,6));
  root.addView(tabBar);

  content=root;
  setContentView(root);
  selectTab(activeTab);
  Tr.tree(this,root);
 }

 private void selectTab(int index){
  activeTab=Math.max(0,Math.min(3,index));
  for(int i=0;i<tabPages.length;i++){
   if(tabPages[i]!=null)tabPages[i].setVisibility(i==activeTab?View.VISIBLE:View.GONE);
  }
  if(tabBar!=null){
   tabBar.removeAllViews();
   String[] titles={"Syncs","Accounts","History","Settings"};
   for(int i=0;i<titles.length;i++){
    final int target=i;
    tabBar.addView(SahlTheme.tabButton(this,titles[i],i==activeTab,()->selectTab(target)));
   }
  }
 }

 private final Map<View,String> searchablePairs=new LinkedHashMap<>();
 private TextView emptySearchLabel;

 private void refreshPairs(EditText searchView){
  if(pairsList==null)return;
  ownerLabels.clear();
  searchablePairs.clear();
  for(Runnable remove:unwatch)remove.run();unwatch.clear();
  pairsList.removeAllViews();
  try{
   JSONArray all=Store.pairs(this);
   if(searchView!=null)searchView.setVisibility(all.length()>1?View.VISIBLE:View.GONE);
   if(all.length()==0){
    pairsList.addView(SahlTheme.label(this,"No folders yet. Choose a folder on your phone, then a destination in OneDrive.",15,SahlTheme.MUTED));
    return;
   }
   for(int i=0;i<all.length();i++){
    JSONObject pair=all.getJSONObject(i);
    String id=pair.getString("id");
    String title=pair.getString("name");
    SyncProvider provider=SyncProvider.from(pair.optString("provider"));

    LinearLayout entry=new LinearLayout(this);
    entry.setOrientation(LinearLayout.VERTICAL);
    int inner=SahlTheme.dp(this,14);entry.setPadding(inner,inner,inner,inner);
    entry.setBackground(SahlTheme.rounded(this,android.graphics.Color.parseColor("#183029")));
    LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,-2);gap.topMargin=SahlTheme.dp(this,10);entry.setLayoutParams(gap);
    pairsList.addView(entry);
    searchablePairs.put(entry,(title+" "+pair.optString("remoteName")+" "+provider.displayName).toLowerCase(Locale.ROOT));

    LinearLayout topRow=new LinearLayout(this);
    topRow.setOrientation(LinearLayout.HORIZONTAL);
    topRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView titleView=SahlTheme.label(this,title,17,SahlTheme.TEXT);
    titleView.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1f));
    topRow.addView(titleView);
    int badgeBg=provider==SyncProvider.GOOGLE_DRIVE?android.graphics.Color.parseColor("#2A3D1F"):android.graphics.Color.parseColor("#1E3A4A");
    topRow.addView(SahlTheme.badge(this,provider.displayName,badgeBg,SahlTheme.TEXT));
    entry.addView(topRow);

    entry.addView(SahlTheme.label(this,directionLabel(pair)+" / "+pair.optString("remoteName")+"\n"+(pair.optBoolean("unmetered")?"Unmetered network":"Any network")+(pair.optBoolean("charging")?" / Charging":"")+(pair.optBoolean("daily")||pair.optInt("scheduleMode")!=0?" / Scheduled":" / Manual"),13,SahlTheme.MUTED));
    TextView owner=SahlTheme.label(this,"",13,SahlTheme.MUTED);
    entry.addView(owner);
    ownerLabels.put(owner,pair);
    updateOwnerLabel(owner,pair);

    TextView manual=SahlTheme.label(this,"",13,SahlTheme.MUTED),scheduled=SahlTheme.label(this,"",13,SahlTheme.MUTED);
    ProgressBar progressBar=SahlTheme.progressBar(this);
    entry.addView(manual);entry.addView(progressBar);entry.addView(scheduled);

    LinearLayout quickRow=new LinearLayout(this);
    quickRow.setOrientation(LinearLayout.HORIZONTAL);
    Button syncNow=SahlTheme.compactButton(this,"Sync now",()->BackupWorker.enqueue(this,pair,false));
    LinearLayout.LayoutParams syncLp=new LinearLayout.LayoutParams(0,-2,1f);
    syncLp.rightMargin=SahlTheme.dp(this,6);
    syncNow.setLayoutParams(syncLp);
    Button stopBtn=SahlTheme.compactSecondary(this,"Stop",()->cancelBackup(pair));
    LinearLayout.LayoutParams stopLp=new LinearLayout.LayoutParams(0,-2,1f);
    stopBtn.setLayoutParams(stopLp);
    quickRow.addView(syncNow);quickRow.addView(stopBtn);
    entry.addView(quickRow);

    LinearLayout actions=new LinearLayout(this);
    actions.setOrientation(LinearLayout.VERTICAL);
    actions.setVisibility(id.equals(openFolder)?View.VISIBLE:View.GONE);
    Button toggle=SahlTheme.compactSecondary(this,"Settings & history"+(id.equals(openFolder)?"  ▴":"  ▾"),()->{});
    toggle.setOnClickListener(view->{
     boolean expanded=actions.getVisibility()!=View.VISIBLE;
     openFolder=expanded?id:null;
     actions.setVisibility(expanded?View.VISIBLE:View.GONE);
     toggle.setText(Tr.t(this,"Settings & history")+(expanded?"  ▴":"  ▾"));
    });
    entry.addView(toggle);
    entry.addView(actions);

    watchWork("manual-"+pair.optString("id"),manual,progressBar,false,false);
    String generation=pair.optString("scheduleGeneration");
    watchWork(generation.isEmpty()?"scheduled-"+id:generation,scheduled,null,true,!generation.isEmpty());
    actions.addView(SahlTheme.compactSecondary(this,"Settings",()->editPair(pair)));
    actions.addView(SahlTheme.compactSecondary(this,"Activity",()->showFolderHistory(pair)));
    actions.addView(SahlTheme.compactSecondary(this,"Version history",()->showVersionFiles(pair)));
    Button remove=SahlTheme.compactSecondary(this,"Remove this folder",()->removePair(pair));
    remove.setTextColor(android.graphics.Color.parseColor("#E8857A"));actions.addView(remove);
   }
   emptySearchLabel=SahlTheme.label(this,"No matching folder backups.",15,SahlTheme.MUTED);
   pairsList.addView(emptySearchLabel);
   filterPairs();
  }catch(Exception e){message("Could not load folder backups.");}
 }

 private void filterPairs(){
  if(searchablePairs.isEmpty())return;
  String query=folderQuery.trim().toLowerCase(Locale.ROOT);
  boolean any=false;
  for(Map.Entry<View,String> item:searchablePairs.entrySet()){
   boolean match=item.getValue().contains(query);
   item.getKey().setVisibility(match?View.VISIBLE:View.GONE);
   any|=match;
  }
  if(emptySearchLabel!=null)emptySearchLabel.setVisibility(any?View.GONE:View.VISIBLE);
 }

 private void refreshAccountsTab(){
  updateAccountStatus();
  updateAccountButtons();
  if(accountsList!=null){
   accountsList.removeAllViews();
   for(CloudAccount acc:allCloudAccounts()){
    LinearLayout row=new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    int p=SahlTheme.dp(this,10);row.setPadding(p,p,p,p);
    row.setBackground(SahlTheme.rounded(this,android.graphics.Color.parseColor("#183029")));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=SahlTheme.dp(this,8);
    row.setLayoutParams(lp);
    TextView lbl=SahlTheme.label(this,"["+acc.provider.displayName+"] "+acc.label,14,SahlTheme.TEXT);
    lbl.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1f));
    row.addView(lbl);
    accountsList.addView(row);
   }
  }
  if(lanSummary!=null){
   try{
    List<LanPairs.Pair> pcs=new LanPairs(getSharedPreferences("lan-sync",MODE_PRIVATE)).list();
    int ready=0;
    for(LanPairs.Pair p:pcs)if(p.ready())ready++;
    lanSummary.setText(ready==0?Tr.t(this,"No paired PCs yet. Scan a QR code from SahlSyncPC to sync over Wi-Fi."):ready+" paired PC folder"+(ready==1?"":"s"));
   }catch(Exception ignored){lanSummary.setText("");}
  }
 }

 private void refreshSettingsStatus(){
  if(settingsStatus==null)return;
  String lang=Tr.language(this);
  String langLabel="ar".equals(lang)?"العربية":"en".equals(lang)?"English":"Auto";
  String bg=backgroundAllowed()?"Allowed":"Restricted";
  settingsStatus.setText(Tr.t(this,"Language")+": "+langLabel+"  ·  "+Tr.t(this,"Allow background backups")+": "+bg);
 }

 private void watchWork(String name,TextView label,ProgressBar bar,boolean periodic,boolean timed){
  androidx.lifecycle.LiveData<List<WorkInfo>> live=timed?WorkManager.getInstance(this).getWorkInfosByTagLiveData(name)
   :WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(name);
  androidx.lifecycle.Observer<List<WorkInfo>> observer=rows->{
   if(isDestroyed())return;
   if(rows==null||rows.isEmpty()){
    if(bar!=null)bar.setVisibility(View.GONE);
    label.setText(periodic?"Schedule: off":"Manual: ready");
    return;
   }
   WorkInfo selected=rows.get(0);
   for(WorkInfo row:rows)if(!row.getState().isFinished()){selected=row;if(row.getState()==WorkInfo.State.RUNNING)break;}
   if(timed&&selected.getState()!=WorkInfo.State.RUNNING){
    for(WorkInfo row:rows)if(row.getState()==WorkInfo.State.ENQUEUED
     &&(selected.getState()!=WorkInfo.State.ENQUEUED||row.getNextScheduleTimeMillis()<selected.getNextScheduleTimeMillis()))selected=row;
   }
   String prefix=timed?"Schedule: ":periodic?"Daily: ":"Manual: ";
   if(bar!=null&&selected.getState()!=WorkInfo.State.RUNNING)bar.setVisibility(View.GONE);
   switch(selected.getState()){
    case RUNNING:
     Data progress=selected.getProgress();
     String file=Objects.toString(progress.getString("file"),"Preparing backup");
     long total=progress.getLong("totalBytes",0);
     String transfer="";
     if(total>0){
      long accepted=progress.getLong("acceptedBytes",0);
      int pct=(int)(accepted*100/total);
      if(bar!=null){bar.setVisibility(View.VISIBLE);bar.setIndeterminate(false);bar.setProgress(pct);}
      String verb=progress.getBoolean("resumed",false)?"Resumed upload":"Uploading";
      transfer=String.format(Locale.getDefault(),"\n%s: %.1f / %.1f MiB (%d%%)",
       verb,accepted/1048576.0,total/1048576.0,pct);
     }else if(bar!=null){
      bar.setVisibility(View.VISIBLE);bar.setIndeterminate(true);
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
   String remoteProvider=SyncProvider.from(pair.optString("provider")).displayName;
   form.addView(SahlTheme.secondary(this,"Change "+remoteProvider+" folder ("+pair.optString("remoteName")+")",()->{
    editingPair=original.optString("id");if(holder[0]!=null)holder[0].dismiss();
    selectCloudAccount("Choose a cloud account",true,owner->chooseCloudRemote(owner,"root",owner.provider.displayName));
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
  connectGoogleButton.setEnabled(GoogleAuth.configured());
  connectGoogleButton.setText(GoogleAuth.configured()?"Add Google Drive account":"Google Drive setup required");
  disconnectButton.setEnabled(!accounts.isEmpty()||!googleAccounts.isEmpty());
  for(Map.Entry<TextView,JSONObject> entry:ownerLabels.entrySet())updateOwnerLabel(entry.getKey(),entry.getValue());
 }

 private boolean pairAccountConnected(JSONObject pair){
  String id=pair.optString("account");
  if(SyncProvider.from(pair.optString("provider"))==SyncProvider.GOOGLE_DRIVE)
   return GoogleAuth.account(this,id)!=null;
  return connectedAccount(id)!=null;
 }

 private void updateOwnerLabel(TextView label,JSONObject pair){
  SyncProvider provider=SyncProvider.from(pair.optString("provider"));
  if(provider==SyncProvider.GOOGLE_DRIVE){
   GoogleAuth.Account owner=GoogleAuth.account(this,pair.optString("account"));
   label.setText(owner==null?"Account: "+pair.optString("accountLabel","Google account")+" (connect to access)"
    :"Account: "+owner.email);
   return;
  }
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

 private void connectGoogle(){
  try{
   GoogleAuth.beginSignIn(this,new GoogleAuth.SignInCallback(){
    @Override public void onSuccess(GoogleAuth.Account account){
     runOnUiThread(()->{
      if(isDestroyed()||isFinishing())return;
      googleAccounts=GoogleAuth.accounts(MainActivity.this);
      updateAccountStatus();
      updateAccountButtons();
     });
    }
    @Override public void onError(String msg){
     runOnUiThread(()->message(msg));
    }
   });
  }catch(Exception e){
   message(e.getMessage()!=null?e.getMessage():"Could not start Google sign-in.");
  }
 }

 private void disconnect(){
  selectCloudAccount("Disconnect which account?",false,owner->Tr.builder(this)
   .setTitle("Disconnect "+owner.provider.displayName+" account?")
   .setMessage("Disconnect "+owner.label+"? Backups for this account will require reconnecting it. Your folder settings stay saved.")
   .setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(dialog,button)->io.execute(()->{
    try{
     synchronized(BackupWorker.class){
      if(owner.provider==SyncProvider.GOOGLE_DRIVE){
       GoogleAuth.removeAccount(this,owner.id);
      }else{
       if(auth==null||!auth.removeAccount(owner.msal))throw new java.io.IOException("Account was not removed");
      }
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
   Uri uri=data.getData();try{takeFolderGrant(data);pendingLocal=uri.toString();selectCloudAccount("Choose a cloud account",true,owner->chooseCloudRemote(owner,"root",owner.provider.displayName));}catch(Exception e){message("Persistent folder access was not granted.");}
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
   Tr.all(this,new String[]{"Upload only (phone to cloud)","Download only (cloud to phone)","Two-way (newest modified wins)"}));
  options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  picker.setAdapter(options);
  picker.setSelection("newest".equals(saved)?2:"download".equals(saved)?1:0);
  form.addView(picker);
  return picker;
 }

 private String directionLabel(JSONObject pair){
  String cloud=SyncProvider.from(pair.optString("provider")).displayName;
  switch(SyncDirection.from(pair.optString("direction"))){
   case DOWNLOAD:return cloud+" to phone";
   case NEWEST:return "Two-way (newest wins)";
   default:return "Phone to "+cloud;
  }
 }

 private boolean applyDirection(JSONObject pair,Spinner direction)throws Exception{
  int selected=direction.getSelectedItemPosition();
  Uri tree=Uri.parse(pair.getString("local"));
  if(selected!=0&&!FolderSync.writable(getContentResolver(),tree)){
   Tr.builder(this).setTitle("Allow writing to the phone folder?")
    .setMessage("Select the same phone folder to allow Sahl Sync to save files from the cloud.")
    .setNegativeButton("Cancel",null).setPositiveButton("Select folder",(dialog,button)->{
     pendingWriteTree=tree.toString();
     pickPhoneFolder(44,tree);
    }).show();
   return false;
  }
  pair.put("direction",selected==2?"newest":selected==1?"download":"upload");
  return true;
 }

 private void chooseCloudRemote(CloudAccount owner,String id,String name){
  if(owner.provider==SyncProvider.GOOGLE_DRIVE)chooseGoogleRemote(owner,id,name);
  else chooseRemote(id,name,owner.msal);
 }

 private void chooseGoogleRemote(CloudAccount owner,String id,String name){
  status.setText("Loading Google Drive folders...");
  io.execute(()->{
   try{
    GoogleDriveDrive gd=new GoogleDriveDrive(this,owner.id,null,null);
    CloudDrive.RemoteItem parent="root".equals(id)?gd.rootFolder():new CloudDrive.RemoteItem(id,name,true,0,0,"","","",true,null);
    List<CloudDrive.RemoteItem> folders=new ArrayList<>();
    for(CloudDrive.RemoteItem item:gd.children(parent.id))if(item.folder)folders.add(item);
    List<String> options=new ArrayList<>();
    options.add("Use this folder");
    options.add("Back to Google Drive root");
    for(CloudDrive.RemoteItem f:folders)options.add(f.name);
    runOnUiThread(()->{
     loadAccount();
     Tr.builder(this).setTitle(name).setItems(options.toArray(new String[0]),(d,index)->{
      try{
       if(index==0&&editingPair!=null)changeRemote(SyncProvider.GOOGLE_DRIVE,parent.id,name,owner.id,owner.label);
       else if(index==0)configurePair(SyncProvider.GOOGLE_DRIVE,parent.id,name,owner.id,owner.label);
       else if(index==1)chooseGoogleRemote(owner,"root","Google Drive");
       else{
        CloudDrive.RemoteItem folder=folders.get(index-2);
        chooseGoogleRemote(owner,folder.id,folder.name);
       }
      }catch(Exception e){message("Could not select folder.");}
     }).setNegativeButton("Cancel",null).show();
    });
   }catch(Exception e){runOnUiThread(()->{loadAccount();message("Could not load Google Drive folders. Check connection and account permission.");});}
  });
 }

 private void chooseRemote(String id,String name,IAccount owner){
  if(auth==null)return;status.setText("Loading OneDrive folders...");
  io.execute(()->{
   try{
    Graph graph=Auth.graph(auth,owner.getId());
    JSONObject parent=graph.json("GET","/me/drive/"+(id.equals("root")?"root":"items/"+Uri.encode(id)),null);
    JSONArray folders=graph.folders(parent.getString("id"));
    List<String> options=new ArrayList<>();options.add("Use this folder");options.add("Back to OneDrive root");for(int i=0;i<folders.length();i++)options.add(folders.getJSONObject(i).getString("name"));
    runOnUiThread(()->{loadAccount();Tr.builder(this).setTitle(name).setItems(options.toArray(new String[0]),(d,index)->{try{if(index==0&&editingPair!=null)changeRemote(SyncProvider.ONEDRIVE,parent.getString("id"),name,owner.getId(),accountLabel(owner));else if(index==0)configurePair(SyncProvider.ONEDRIVE,parent.getString("id"),name,owner.getId(),accountLabel(owner));else if(index==1)chooseRemote("root","OneDrive",owner);else{JSONObject folder=folders.getJSONObject(index-2);chooseRemote(folder.getString("id"),folder.getString("name"),owner);}}catch(Exception e){message("Could not select folder.");}}).setNegativeButton("Cancel",null).show();});
   }catch(Exception e){runOnUiThread(()->{loadAccount();message("Could not load OneDrive folders. Check connection and account permission.");});}
  });
 }
 private String editingPair;
 private void changeRemote(SyncProvider provider,String remote,String remoteName,String accountId,String accountLabel)throws Exception{
  String id=editingPair;editingPair=null;
  JSONObject pair=Store.pair(this,id).put("provider",provider.id).put("remote",remote).put("remoteName",remoteName)
   .put("account",accountId).put("accountLabel",accountLabel);
  changeFolders(pair,provider.displayName+" folder changed to "+remoteName);
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
 private void configurePair(SyncProvider provider,String remote,String remoteName,String accountId,String accountLabel){
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
    JSONObject pair=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",title).put("provider",provider.id).put("local",pendingLocal).put("remote",remote).put("remoteName",remoteName).put("account",accountId).put("accountLabel",accountLabel).put("exclude",exclude.getText().toString()).put("unmetered",wifi.isChecked()).put("charging",charging.isChecked());
    if(!applyDirection(pair,direction))return;
    long due=schedule.apply(pair);
    synchronized(BackupSchedule.class){Store.add(this,pair);if(due>0)BackupSchedule.enqueue(this,pair,due);}dialog.dismiss();draw();loadAccount();if(due>0)offerBackgroundSetup();
   }catch(IllegalArgumentException e){message(e.getMessage());}catch(Exception e){message("Could not save folder backup.");}
  }));dialog.show();
 }
 private CloudDrive driveForPair(JSONObject pair)throws Exception{
  SyncPair syncPair=SyncPair.fromJson(pair);
  if(syncPair.provider==SyncProvider.GOOGLE_DRIVE)return new GoogleDriveDrive(this,syncPair.account,syncPair.id,null);
  if(auth==null)throw new java.io.IOException("Sign-in unavailable");
  return new OneDriveDrive(Auth.graph(auth,syncPair.account));
 }
 private void showVersionFiles(JSONObject pair){
  if(!pairAccountConnected(pair)){message("Connect the cloud account for this folder first.");return;}
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
    List<CloudDrive.RemoteItem> versions=CloudBackup.versions(driveForPair(pair),SyncPair.fromJson(pair),file.getString("path"));
    runOnUiThread(()->{
     if(isFinishing()||isDestroyed())return;
     if(versions.isEmpty()){message("No older copies yet. On the next change, Sahl Sync archives the current file before updating it. Old hash-named alpha copies are unchanged.");return;}
     try{
      String[] labels=new String[versions.size()];
      for(int i=0;i<labels.length;i++){CloudDrive.RemoteItem version=versions.get(i);String filename=version.name;long date=Long.parseLong(filename.substring(0,filename.indexOf('-')));labels[i]=java.text.DateFormat.getDateTimeInstance().format(new Date(date))+" - "+version.size+" bytes";}
      Tr.builder(this).setTitle(file.getString("name")+" - older copies").setItems(labels,(d,index)->{
       try{CloudDrive.RemoteItem version=versions.get(index);Tr.builder(this).setTitle("Save older version?").setMessage("Save this version as a separate file on your phone. Your current file and cloud backup stay unchanged.").setNegativeButton("Cancel",null).setPositiveButton("Save a copy",(dialog,which)->downloadSnapshot(pair,file,version)).show();}catch(Exception e){message("Could not select version.");}
      }).setNegativeButton("Close",null).show();
     }catch(Exception e){message("Could not display versions.");}
    });
   }catch(Exception e){runOnUiThread(()->message("Could not load version history. Check your connection and account."));}
  });
 }
 private void downloadSnapshot(JSONObject pair,JSONObject file,CloudDrive.RemoteItem snapshot){
  io.execute(()->{
   java.io.File cached=null;
   try{
    cached=java.io.File.createTempFile("restore-",".tmp",getCacheDir());
    String hash=driveForPair(pair).download(snapshot.id,cached);
    if(!hash.equals(VersionedBackup.snapshotHash(snapshot.name)))throw new java.io.IOException("Stored version checksum mismatch");
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
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("activeTab",activeTab);out.putString("local",pendingLocal);out.putString("writePair",pendingWriteTree);if(pendingSnapshot!=null)out.putString("snapshot",pendingSnapshot.getAbsolutePath());out.putString("snapshotName",pendingSnapshotName);}
 @Override protected void onDestroy(){for(Runnable remove:unwatch)remove.run();unwatch.clear();Store.prefs(this).unregisterOnSharedPreferenceChangeListener(historyListener);io.shutdown();super.onDestroy();}
}
