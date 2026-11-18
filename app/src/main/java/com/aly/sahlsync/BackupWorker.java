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
 private String current="Connecting";
 private void progress(){setProgressAsync(new Data.Builder().putString("file",current.length()>500?current.substring(0,500)+"...":current).putInt("uploaded",uploaded).putInt("unchanged",unchanged).putInt("conflicts",conflicts).build());}
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
   BackupCancellation.bind(this::isStopped);
   try{
    BackupCancellation.check();progress();
    JSONObject pair=Store.pair(c,getInputData().getString("pair"));
    IAuthenticationResult auth=Auth.token(Auth.open(c));
    if(!auth.getAccount().getId().equals(pair.getString("account")))throw new IOException("Account changed; recreate this folder pair");
    DocumentFile local=DocumentFile.fromTreeUri(c,Uri.parse(pair.getString("local")));
    if(local==null||!local.isDirectory()||!local.canRead())throw new IOException("Local folder permission is unavailable; select it again");
    Graph graph=new Graph(auth.getAccessToken());
    graph.enableResumableUploads(c,getInputData().getString("pair"));
    walk(c,graph,local.getUri(),pair.getString("remote"),"",pair,0);
    BackupCancellation.check();
    Store.history(c,getInputData().getString("pair"),"Backup finished: "+uploaded+" uploaded, "+unchanged+" unchanged, "+excluded+" excluded, "+conflicts+" conflicts.");
    return conflicts==0?Result.success(new Data.Builder().putString("summary","Completed: "+uploaded+" uploaded, "+unchanged+" unchanged").build()):Result.failure(new Data.Builder().putString("summary",conflicts+" conflicts - see History").build());
   }catch(Graph.HttpError e){
    if(isStopped())return Result.failure();
    boolean retry=e.status==429||e.status>=500;
    Store.history(c,getInputData().getString("pair"),current+": "+(retry&&getRunAttemptCount()<3?"OneDrive is busy. Automatic retry queued.":"OneDrive HTTP "+e.status+". Check connection, sign-in, quota and destination access; then tap Back up now to retry."));
    return retry&&getRunAttemptCount()<3?Result.retry():Result.failure();
   }catch(UploadSession.ConnectionFailure e){
    boolean retry=!isStopped()&&getRunAttemptCount()<3;
    Store.history(c,getInputData().getString("pair"),current+": "+(retry
     ?"Upload interrupted. Automatic retry queued."
     :"Upload interrupted. Tap Back up now to retry."));
    return retry?Result.retry():Result.failure();
   }catch(Exception e){
    Store.history(c,getInputData().getString("pair"),isStopped()?"Backup stopped; completed uploads are kept.":current+": stopped after "+uploaded+" uploads. "+failureHint(e)+" Tap Back up now to retry; completed files are kept.");
    return Result.failure();
   }finally{BackupCancellation.clear();}
  }
 }
 private static String failureHint(Exception e){
  if(e instanceof IllegalArgumentException)return "Check unsupported file names.";
  if(e instanceof com.microsoft.identity.client.exception.MsalException)return "Connect OneDrive again.";
  if(e instanceof java.net.SocketTimeoutException||e instanceof java.net.UnknownHostException)return "Check your network connection.";
  return "Check folder access, free storage and the 128 MiB file limit.";
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
    if(relative.equals(OneDriveBackup.HISTORY)||relative.startsWith(OneDriveBackup.HISTORY+"/")||BackupRules.excluded(relative,pair.optString("exclude"))){excluded++;continue;}
    current=relative;progress();BackupRules.validateName(name);
    Uri uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(source,cursor.getString(0));
    if(android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))){
     walk(c,graph,uri,graph.folder(remote,name),relative+"/",pair,depth+1);
    }else upload(c,graph,uri,remote,name,relative,pair);
   }
  }
 }
 private void upload(Context c,Graph graph,Uri uri,String parent,String name,String relative,JSONObject pair)throws Exception{
  File stage=File.createTempFile("backup-",".tmp",c.getCacheDir());
  try{
   String hash;try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(stage)){
    if(in==null)throw new IOException("Cannot open file");hash=BackupRules.hash(in,out);
   }
   if(isStopped())throw new InterruptedException();
   JSONObject saved=Store.receipt(c,pair.getString("id"),relative);
   VersionedBackup.Receipt known=saved==null?null:new VersionedBackup.Receipt(saved.getString("id"),saved.optString("tag"),saved.getString("hash"),saved.getLong("size"));
   VersionedBackup.Result result;
   try{result=VersionedBackup.backup(new OneDriveBackup(c,graph,pair,parent,relative,name),name,stage,hash,known);}
   catch(Graph.HttpError e){if(e.status!=409&&e.status!=412)throw e;conflicts++;Store.history(c,getInputData().getString("pair"),relative+": cloud conflict (HTTP "+e.status+"). No overwrite. Check the cloud copy before retrying.");return;}
   switch(result.outcome){
    case CREATED: case UPDATED: uploaded++;break;
    case UNCHANGED: unchanged++;break;
    case CONFLICT: conflicts++;Store.history(c,getInputData().getString("pair"),relative+": cloud contents changed or belong to an unknown file. No overwrite. Check the cloud copy before retrying.");return;
   }
   Store.receipt(c,pair.getString("id"),relative,name,result.receipt);
   progress();
  }finally{stage.delete();}
 }
}