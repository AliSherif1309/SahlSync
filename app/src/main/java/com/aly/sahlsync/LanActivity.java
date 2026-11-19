package com.aly.sahlsync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import androidx.documentfile.provider.DocumentFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LanActivity extends Activity {
 private static final int CHOOSE_FOLDER=70, EXPORT_VERSION=71;
 private static final ExecutorService executor=Executors.newSingleThreadExecutor();
 private static boolean running;
 private static volatile boolean stopRequested;
 private ProgressBar bar,fileBar;
 private Button stop;
 private final android.os.Handler ticker=new android.os.Handler(android.os.Looper.getMainLooper());
 private final Runnable tick=new Runnable(){
  @Override public void run(){
   if(!running||isDestroyed()){bar.setVisibility(android.view.View.GONE);fileBar.setVisibility(android.view.View.GONE);stop.setVisibility(android.view.View.GONE);return;}
   bar.setVisibility(android.view.View.VISIBLE);stop.setVisibility(android.view.View.VISIBLE);
   int todo=LanFolderSync.todo,done=LanFolderSync.done;
   if(todo>0){bar.setIndeterminate(false);bar.setMax(todo);bar.setProgress(done);}else bar.setIndeterminate(true);
   long total=LanPeer.total,moved=LanPeer.moved;
   if(total>1024*1024&&moved<total){
    fileBar.setVisibility(android.view.View.VISIBLE);fileBar.setMax(1000);fileBar.setProgress((int)(moved*1000/total));
    bytes.setText(LanPeer.current+"  "+(moved>>20)+" / "+(total>>20)+" MB");bytes.setVisibility(android.view.View.VISIBLE);
   }else{fileBar.setVisibility(android.view.View.GONE);bytes.setVisibility(android.view.View.GONE);}
   ticker.postDelayed(this,300);
  }
 };
 private TextView bytes;
 private LanPairs pairs;
 private LanPairs.Pair selected;
 private TextView connection,folder,progress,lastRun;
 private Button pair,pick,sync,mode,history,selectPair,addPair,syncAll;
 private String pendingVersion,pendingExportPair,pendingFolderPair;

 @Override protected void onCreate(Bundle saved){
  super.onCreate(saved);
  if(saved!=null){
   pendingVersion=saved.getString("pendingVersion");
   pendingExportPair=saved.getString("pendingExportPair");
   pendingFolderPair=saved.getString("pendingFolderPair");
  }
  pairs=new LanPairs(getSharedPreferences("lan-sync",MODE_PRIVATE));
  ScrollView scroll=new ScrollView(this);
  LinearLayout content=new LinearLayout(this);
  content.setOrientation(LinearLayout.VERTICAL);
  int padding=SahlTheme.dp(this,20);
  content.setPadding(padding,padding,padding,padding);
  scroll.addView(content);
  setContentView(scroll);
  content.addView(SahlTheme.label(this,"Sync with my PC",26,SahlTheme.TEXT));
  selectPair=SahlTheme.secondary(this,"",this::choosePair);
  content.addView(selectPair);
  addPair=SahlTheme.secondary(this,"Add folder pair",this::addPair);
  content.addView(addPair);
  connection=SahlTheme.label(this,"",15,SahlTheme.MUTED);
  content.addView(connection);
  content.addView(SahlTheme.button(this,"Scan QR from PC",this::scanCode));
  pair=SahlTheme.secondary(this,"Enter pairing code",()->enterCode(""));
  content.addView(pair);
  folder=SahlTheme.label(this,"",15,SahlTheme.MUTED);
  content.addView(folder);
  pick=SahlTheme.secondary(this,"Choose phone folder",this::pickFolder);
  content.addView(pick);
  mode=SahlTheme.secondary(this,"",this::chooseMode);
  content.addView(mode);
  options=SahlTheme.secondary(this,"Auto sync & options",this::chooseOptions);
  content.addView(options);
  sync=SahlTheme.button(this,"Sync now",()->startSync(false));
  content.addView(sync);
  syncAll=SahlTheme.secondary(this,"Sync all folders",()->startSync(true));
  content.addView(syncAll);
  history=SahlTheme.secondary(this,"Version history",this::showVersions);
  removeButton=SahlTheme.secondary(this,"Remove this folder",this::removeSelected);
  removeButton.setTextColor(android.graphics.Color.parseColor("#E8857A"));
  content.addView(history);
  content.addView(removeButton);
  lastRun=SahlTheme.label(this,"",15,SahlTheme.MUTED);
  content.addView(lastRun);
  bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
  bar.setProgressTintList(android.content.res.ColorStateList.valueOf(SahlTheme.GOLD));
  bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(SahlTheme.GOLD));
  bar.setVisibility(android.view.View.GONE);
  content.addView(bar);
  fileBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
  fileBar.setProgressTintList(android.content.res.ColorStateList.valueOf(SahlTheme.MUTED));
  fileBar.setVisibility(android.view.View.GONE);
  content.addView(fileBar);
  bytes=SahlTheme.label(this,"",13,SahlTheme.MUTED);bytes.setVisibility(android.view.View.GONE);content.addView(bytes);
  progress=SahlTheme.label(this,"",16,SahlTheme.TEXT);
  content.addView(progress);
  stop=SahlTheme.secondary(this,"Stop",()->{stopRequested=true;progress.setText(Tr.t(this,"Stopping after the current step…"));});
  stop.setVisibility(android.view.View.GONE);
  content.addView(stop);
  refresh();
 }

 @Override protected void onResume(){super.onResume();if(progress!=null)refresh();}

 private void refresh(){
  try{
   List<LanPairs.Pair> all=pairs.list();
   selected=pairs.get(pairs.activeId());
   if(selected==null)selected=all.get(0);
   selectPair.setText("Folder pair: "+selected.name+" ("+all.size()+")");
   connection.setText(selected.code.isEmpty()?"No PC paired":"PC pairing saved");
   DocumentFile tree=selected.tree.isEmpty()?null:DocumentFile.fromTreeUri(this,Uri.parse(selected.tree));
   folder.setText(tree==null?"No phone folder selected":"Phone folder: "+tree.getName());
   if(!running)progress.setText(selected.result);
   else{ticker.removeCallbacks(tick);ticker.post(tick);}
   lastRun.setText(selected.lastCompleted==0?"":"Last completed sync: "+
    java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(selected.lastCompleted)));
   mode.setText("Direction: "+selectedMode().label);
   options.setText("Auto sync: "+autoLabel(selected.autoMinutes)+(selected.deletions?" · Deletions sync":" · Deletions off"));
   syncAll.setVisibility(all.size()>1?android.view.View.VISIBLE:android.view.View.GONE);
   setControls(!running);
   LanWorker.schedule(getApplicationContext(),selected);
   Tr.tree(this,getWindow().getDecorView());
   history.setEnabled(!running&&selected.ready());
   sync.setEnabled(!running&&selected.ready());
   syncAll.setEnabled(!running&&all.stream().anyMatch(LanPairs.Pair::ready));
  }catch(Exception error){
   progress.setText("Could not load Wi-Fi folder pairs.");
   setControls(false);
  }
 }

 private void setControls(boolean enabled){
  for(Button button:new Button[]{selectPair,addPair,pair,pick,mode,options,sync,syncAll,history,removeButton}){
   button.setEnabled(enabled);button.setAlpha(enabled?1f:0.4f);
  }
 }

 private Button options;
 private static final int[] AUTO={0,15,60,360,1440};
 private static String autoLabel(int minutes){
  return minutes<=0?"Off":minutes<60?"every "+minutes+" min":minutes<1440?"every "+(minutes/60)+" h":"daily";
 }
 private void chooseOptions(){
  LanPairs.Pair target=selected;
  LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
  int pad=SahlTheme.dp(this,20);form.setPadding(pad,pad,pad,0);
  form.addView(SahlTheme.label(this,"Auto sync (on Wi-Fi, in the background)",15,SahlTheme.TEXT));
  Spinner auto=new Spinner(this);
  String[] labels=new String[AUTO.length];for(int i=0;i<AUTO.length;i++)labels[i]=autoLabel(AUTO[i]);
  ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,Tr.all(this,labels));auto.setAdapter(adapter);
  for(int i=0;i<AUTO.length;i++)if(AUTO[i]==target.autoMinutes)auto.setSelection(i);
  form.addView(auto);
  CheckBox charging=new CheckBox(this);charging.setText("Auto sync only while charging");charging.setChecked(target.autoCharging);form.addView(charging);
  CheckBox deletions=new CheckBox(this);deletions.setText("Sync deletions (two-way mode). Deleted files stay in Version history.");
  deletions.setChecked(target.deletions);form.addView(deletions);
  EditText name=new EditText(this);name.setHint("Folder pair name");name.setText(target.name);name.setSingleLine(true);form.addView(name);
  Tr.builder(this).setTitle("Options").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
   target.autoMinutes=AUTO[auto.getSelectedItemPosition()];
   target.autoCharging=charging.isChecked();
   target.deletions=deletions.isChecked();
   String title=name.getText().toString().trim();if(!title.isEmpty())target.name=title;
   try{pairs.update(target);LanWorker.schedule(getApplicationContext(),target);}catch(Exception e){progress.setText("Could not save options.");}
   refresh();
  }).show();
 }

 private Button removeButton;
 private void removeSelected(){
  LanPairs.Pair target=selected;
  if(target==null)return;
  Tr.builder(this).setTitle("Remove this folder?")
   .setMessage("Sahl Sync stops syncing it with your PC. Your files on the phone and the PC are not deleted.")
   .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{
    try{
     target.autoMinutes=0;LanWorker.schedule(getApplicationContext(),target);
     pairs.remove(target.id);
     if(pairs.list().isEmpty())pairs.add("Folder pair 1");
     refresh();
    }catch(Exception e){progress.setText("Could not remove the folder.");}
   }).show();
 }

 private void choosePair(){
  try{
   List<LanPairs.Pair> all=pairs.list();
   String[] names=new String[all.size()];
   for(int i=0;i<names.length;i++)names[i]=all.get(i).name;
   Tr.builder(this).setTitle("Folder pairs").setItems(names,(dialog,index)->{
    pairs.select(all.get(index).id);
    refresh();
   }).setNegativeButton("Close",null).show();
  }catch(Exception error){progress.setText("Could not open folder pairs.");}
 }

 private void addPair(){
  EditText name=new EditText(this);
  name.setSingleLine(true);
  name.setHint("Folder pair name");
  Tr.builder(this).setTitle("Add folder pair").setView(name)
   .setPositiveButton("Add",(dialog,which)->{
    try{
     String label=name.getText().toString().trim();
     if(label.isEmpty())label="Folder pair "+(pairs.list().size()+1);
     pairs.select(pairs.add(label).id);
     refresh();
    }catch(Exception error){progress.setText("Could not add the folder pair.");}
   }).setNegativeButton("Cancel",null).show();
 }

 private void pickFolder(){
  pendingFolderPair=selected.id;
  startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
   |Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),CHOOSE_FOLDER);
 }

 private boolean sameCredential(String first,String second){
  String[] a=first.split("\\s+"),b=second.split("\\s+");
  return a.length==4&&b.length==4&&a[2].equals(b[2])&&a[3].equals(b[3]);
 }

 private void scanCode(){
  new com.google.zxing.integration.android.IntentIntegrator(this)
   .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
   .setPrompt("Scan the QR shown in Sahl Sync on your PC").setBeepEnabled(false).setOrientationLocked(false).initiateScan();
 }

 private void enterCode(String value){
  LanPairs.Pair target=selected;
  EditText input=new EditText(this);
  input.setSingleLine(true);
  input.setHint("Pairing code from your PC");
  input.setText(value);
  AlertDialog dialog=Tr.builder(this).setTitle("Pair with your PC").setView(input)
   .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
  dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
   String code=input.getText().toString().trim();
   try{
    LanPeer peer=new LanPeer(code);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    setControls(false);
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
      try{
       if(!sameCredential(target.code,code)){
        target.legacyArchive=false;
        target.archiveKey=UUID.randomUUID().toString();
        target.result="";
        target.lastCompleted=0;
       }
       target.code=code;
       pairs.update(target);
      }catch(Exception error){progress.setText("Could not save pairing.");return;}
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
  com.google.zxing.integration.android.IntentResult scan=
   com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(request,result,data);
  if(scan!=null){if(scan.getContents()!=null)enterCode(scan.getContents().trim());return;}
  if(request==EXPORT_VERSION){
   String version=pendingVersion;
   pendingVersion=null;
   String pairId=pendingExportPair;
   pendingExportPair=null;
   if(version!=null&&pairId!=null&&result==RESULT_OK&&data!=null&&data.getData()!=null)exportVersion(pairId,version,data.getData());
   return;
  }
  if(request!=CHOOSE_FOLDER)return;
  String pairId=pendingFolderPair;
  pendingFolderPair=null;
  if(pairId==null||result!=RESULT_OK||data==null||data.getData()==null)return;
  try{
   LanPairs.Pair target=pairs.get(pairId);
   if(target==null)return;
   Uri uri=data.getData();
   int required=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
   if((data.getFlags()&required)!=required)throw new SecurityException();
   getContentResolver().takePersistableUriPermission(uri,
    Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
   if(!uri.toString().equals(target.tree)){
    target.tree=uri.toString();
    target.result="";
    target.lastCompleted=0;
    pairs.update(target);
   }
   refresh();
  }catch(Exception error){progress.setText("Choose a writable folder on your phone.");}
 }

 @Override protected void onSaveInstanceState(Bundle out){
  super.onSaveInstanceState(out);
  out.putString("pendingVersion",pendingVersion);
  out.putString("pendingExportPair",pendingExportPair);
  out.putString("pendingFolderPair",pendingFolderPair);
 }

 private LanFolderSync.Mode selectedMode(){
  return LanFolderSync.Mode.from(selected.mode);
 }

 private void chooseMode(){
  LanPairs.Pair target=selected;
  LanFolderSync.Mode[] modes=LanFolderSync.Mode.values();
  String[] labels=new String[modes.length];
  for(int i=0;i<modes.length;i++)labels[i]=modes[i].label;
  Tr.builder(this).setTitle("Sync mode")
   .setSingleChoiceItems(labels,selectedMode().ordinal(),(dialog,index)->{
    try{
     target.mode=modes[index].name();
     pairs.update(target);
     dialog.dismiss();
     refresh();
    }catch(Exception error){progress.setText("Could not save the sync mode.");}
   }).setNegativeButton("Cancel",null).show();
 }

 private String archiveKey(LanPairs.Pair item,LanPeer peer){return LanRunner.archiveKey(item,peer);}

 private LanVersions versions(LanPairs.Pair item)throws Exception{
  return new LanVersions(getApplicationContext(),archiveKey(item,new LanPeer(item.code)),item.tree);
 }

 private void showVersions(){
  LanPairs.Pair target=selected;
  executor.execute(()->{
   try{
    List<LanVersions.Version> versions=versions(target).list();
    runOnUiThread(()->{
     if(isDestroyed())return;
     AlertDialog.Builder dialog=Tr.builder(this).setTitle("Version history");
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
       pendingExportPair=target.id;
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

 private void exportVersion(String pairId,String id,Uri destination){
  LanPairs.Pair target;
  try{target=pairs.get(pairId);}catch(Exception error){progress.setText("Could not load this folder pair.");return;}
  if(target==null)return;
  executor.execute(()->{
   String message="Could not export the older copy.";
   try{
    LanVersions historyStore=versions(target);
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

 private void startSync(boolean all){
  if(running)return;
  List<LanPairs.Pair> queue=new ArrayList<>();
  try{
   if(all){
    for(LanPairs.Pair item:pairs.list())if(item.ready())queue.add(item);
   }else if(selected.ready())queue.add(selected);
  }catch(Exception error){progress.setText("Could not load Wi-Fi folder pairs.");return;}
  if(queue.isEmpty())return;
  running=true;stopRequested=false;
  progress.setText(Tr.t(this,"Starting…"));
  refresh();
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  executor.execute(()->{
   for(LanPairs.Pair item:queue){
    if(stopRequested)break;
    synchronized(LanWorker.class){
     BackupCancellation.bind(()->stopRequested);
     LanRunner.run(getApplicationContext(),pairs,item,
      label->runOnUiThread(()->{if(!isDestroyed())progress.setText(item.name+"\n"+Tr.t(this,label));}));
     BackupCancellation.clear();
     if(stopRequested){item.result=Tr.t(this,"Stopped. The next sync continues where it left off.");try{pairs.update(item);}catch(Exception ignored){}}
    }
   }
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
