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
 private String pendingLocal;
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);if(saved!=null)pendingLocal=saved.getString("local");
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
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
  scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{SahlTheme.TOP,SahlTheme.BOTTOM}));
  scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);int p=SahlTheme.dp(this,20);content.setPadding(p,p,p,p);scroll.addView(content);setContentView(scroll);
  content.addView(SahlTheme.label(this,"SAHL  /  SYNC",14,SahlTheme.GOLD));
  content.addView(SahlTheme.label(this,"Your folders. Backed up.",30,SahlTheme.TEXT));
  content.addView(SahlTheme.label(this,"OneDrive backup • Early preview",15,SahlTheme.MUTED));
  LinearLayout connect=card("Microsoft account");status=SahlTheme.label(this,Auth.configured()?"Loading account…":"Microsoft sign-in setup is pending for this build.",16,SahlTheme.MUTED);connect.addView(status);
  connect.addView(SahlTheme.button(this,"Connect OneDrive",this::connect));
  connect.addView(SahlTheme.button(this,"Disconnect",this::disconnect));
  LinearLayout pairs=card("Folder backups");
  pairs.addView(SahlTheme.label(this,"Upload-only. No local or cloud deletions. Changed files get separate content-versioned names. Alpha limit: 20 MiB per file and 5,000 items per run.",15,SahlTheme.MUTED));
  pairs.addView(SahlTheme.button(this,"Add folder backup",()->{
   if(account==null){message("Connect OneDrive first.");return;}
   startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),42);
  }));
  try{
   JSONArray all=Store.pairs(this);
   if(all.length()==0)pairs.addView(SahlTheme.label(this,"No folders yet. Choose a folder on your phone, then a destination in OneDrive.",16,SahlTheme.MUTED));
   for(int i=0;i<all.length();i++){
    JSONObject pair=all.getJSONObject(i);
    pairs.addView(SahlTheme.label(this,pair.getString("name"),18,SahlTheme.TEXT));
    pairs.addView(SahlTheme.label(this,"Phone → "+pair.getString("remoteName")+"\n"+(pair.optBoolean("unmetered")?"Unmetered network":"Any network")+(pair.optBoolean("charging")?" • Charging":"")+(pair.optBoolean("daily")?" • Daily":" • Manual"),14,SahlTheme.MUTED));
    pairs.addView(SahlTheme.button(this,"Back up now",()->{BackupWorker.enqueue(this,pair,false);message("Backup queued. It will run when the selected network and battery conditions are met.");}));
    pairs.addView(SahlTheme.button(this,"Pause scheduled backup",()->{WorkManager.getInstance(this).cancelUniqueWork("scheduled-"+pair.optString("id"));message("Scheduled backup paused. Manual backup is still available.");}));
    pairs.addView(SahlTheme.button(this,"Resume daily backup",()->{BackupWorker.enqueue(this,pair,true);message("Daily backup enabled. Android controls the exact start time.");}));
   }
  }catch(Exception e){message("Could not load folder backups.");}
  LinearLayout log=card("History");history=SahlTheme.label(this,"",15,SahlTheme.MUTED);log.addView(history);refreshHistory();
  log.addView(SahlTheme.button(this,"Refresh history",this::refreshHistory));
 }
 private void refreshHistory(){
  try{JSONArray all=new JSONArray(Store.prefs(this).getString("history","[]"));StringBuilder text=new StringBuilder();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);text.append(java.text.DateFormat.getDateTimeInstance().format(new Date(row.getLong("time")))).append('\n').append(row.getString("message")).append("\n\n");}history.setText(text.length()==0?"No backup runs yet.":text);}
  catch(Exception e){history.setText("History is unavailable.");}
 }
 private void updateAccountButtons(){
  if(connectButton==null)return;
  connectButton.setEnabled(auth!=null&&account==null);connectButton.setText(!Auth.configured()?"OneDrive setup required":auth==null?"Preparing OneDrive…":account==null?"Connect OneDrive":"Connected");
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
  new AlertDialog.Builder(this).setTitle("Disconnect OneDrive?").setMessage("Stop queued backups and remove this app’s saved Microsoft session. Existing files remain in OneDrive.").setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(d,w)->{
   WorkManager.getInstance(this).cancelAllWork();
   io.execute(()->{try{synchronized(BackupWorker.class){auth.signOut();}runOnUiThread(()->{account=null;loadAccount();});}catch(Exception e){runOnUiThread(()->message("Could not disconnect. Try again."));}});
  }).show();
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){
   Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);pendingLocal=uri.toString();chooseRemote("root","OneDrive");}catch(Exception e){message("Persistent folder access was not granted.");}
  }
 }
 private void chooseRemote(String id,String name){
  if(auth==null||account==null)return;status.setText("Loading OneDrive folders…");
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
 private void message(String text){if(!isFinishing())new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK",null).show();}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("local",pendingLocal);}
 @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
