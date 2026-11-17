package com.aly.sahlsync;

import android.content.*;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.*;
import com.microsoft.identity.client.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class BackupWorker extends Worker {
 private int uploaded,unchanged,excluded,conflicts,visited;
 private final Set<String> seen=new HashSet<>();
 public BackupWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
 static Constraints constraints(JSONObject pair){return new Constraints.Builder().setRequiredNetworkType(pair.optBoolean("unmetered",true)?NetworkType.UNMETERED:NetworkType.CONNECTED).setRequiresCharging(pair.optBoolean("charging",false)).setRequiresBatteryNotLow(true).build();}
 static void enqueue(Context c,JSONObject pair,boolean periodic){
  String id=pair.optString("id");Data data=new Data.Builder().putString("pair",id).build();
  WorkManager wm=WorkManager.getInstance(c);
  if(periodic)wm.enqueueUniquePeriodicWork("scheduled-"+id,ExistingPeriodicWorkPolicy.UPDATE,new PeriodicWorkRequest.Builder(BackupWorker.class,24,TimeUnit.HOURS).setInputData(data).setConstraints(constraints(pair)).build());
  else wm.enqueueUniqueWork("manual-"+id,ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(BackupWorker.class).setInputData(data).setConstraints(constraints(pair)).build());
 }
 @NonNull @Override public Result doWork(){
  Context c=getApplicationContext();
  // A process-wide lock also serializes manual and periodic requests for different pairs.
  synchronized(BackupWorker.class){
   try{
    JSONObject pair=Store.pair(c,getInputData().getString("pair"));
    IAuthenticationResult auth=Auth.token(Auth.open(c));
    if(!auth.getAccount().getId().equals(pair.getString("account")))throw new IOException("Account changed; recreate this folder pair");
    DocumentFile local=DocumentFile.fromTreeUri(c,Uri.parse(pair.getString("local")));
    if(local==null||!local.isDirectory()||!local.canRead())throw new IOException("Local folder permission is unavailable; select it again");
    Graph graph=new Graph(auth.getAccessToken());
    walk(c,graph,local.getUri(),pair.getString("remote"),"",pair,0);
    Store.history(c,"Backup finished: "+uploaded+" uploaded, "+unchanged+" unchanged, "+excluded+" excluded, "+conflicts+" conflicts.");
    return conflicts==0?Result.success():Result.failure();
   }catch(Graph.HttpError e){
    boolean retry=e.status==429||e.status>=500;
    Store.history(c,retry?"OneDrive is busy. Backup will retry.":"OneDrive rejected the request (HTTP "+e.status+"). Check sign-in and folder access.");
    return retry&&getRunAttemptCount()<3?Result.retry():Result.failure();
   }catch(Exception e){
    Store.history(c,isStopped()?"Backup stopped; completed uploads are kept.":"Backup stopped after "+uploaded+" uploads. Check sign-in, folder access, storage, file names and the 20 MiB file limit.");
    return Result.failure();
   }
  }
 }
 private void walk(Context c,Graph graph,Uri source,String remote,String path,JSONObject pair,int depth)throws Exception{
  if(depth>32||!seen.add(source.toString()))throw new IOException("Folder nesting limit reached");
  // Use the provider cursor directly: listFiles() can silently return an empty array on a failed query.
  Uri children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(source,android.provider.DocumentsContract.getDocumentId(source));
  try(android.database.Cursor cursor=c.getContentResolver().query(children,new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
   if(cursor==null)throw new IOException("Cannot list local folder");
   while(cursor.moveToNext()){
    if(isStopped()||Thread.currentThread().isInterrupted())throw new InterruptedException();
    if(++visited>5000)throw new IOException("Alpha limit: 5000 items per run");
    String name=cursor.getString(1),relative=path+name;
    if(BackupRules.excluded(relative,pair.optString("exclude"))){excluded++;continue;}
    BackupRules.validateName(name);
    Uri uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(source,cursor.getString(0));
    if(android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))){
     walk(c,graph,uri,graph.folder(remote,name),relative+"/",pair,depth+1);
    }else upload(c,graph,uri,remote,name,pair);
   }
  }
 }
 private void upload(Context c,Graph graph,Uri uri,String parent,String name,JSONObject pair)throws Exception{
  File stage=File.createTempFile("backup-",".tmp",c.getCacheDir());
  try{
   String hash;
   try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(stage)){
    if(in==null)throw new IOException("Cannot open file");hash=BackupRules.hash(in,out);
   }
   String version=BackupRules.versionName(name,hash);
   String key="done:"+pair.getString("account")+":"+parent+":"+version;
   try{
    JSONObject existing=graph.item(parent,version);
    String known=Store.prefs(c).getString(key,"");
    if(RemoteFileVerifier.unchanged(stage.length(),hash,existing.optLong("size",-1),known,existing.optString("eTag"),()->graph.contentHash(existing.getString("id")))){
     if(!Store.prefs(c).edit().putString(key,existing.optString("eTag")).commit())throw new IOException("Cannot record verified upload");
     unchanged++;
    }
    else conflicts++;
    return;
   }catch(Graph.HttpError e){if(e.status!=404)throw e;}
   JSONObject result;
   try{result=graph.upload(parent,version,stage);}catch(Graph.HttpError e){if(e.status!=409&&e.status!=412)throw e;conflicts++;return;}
   String tag=result.getString("eTag");
   if(!Store.prefs(c).edit().putString(key,tag).commit())throw new IOException("Cannot record completed upload");
   uploaded++;
   setProgressAsync(new Data.Builder().putInt("uploaded",uploaded).build());
  }finally{stage.delete();}
 }
}
