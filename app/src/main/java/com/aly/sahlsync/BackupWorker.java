package com.aly.sahlsync;

import android.content.*;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.*;
import com.microsoft.identity.client.exception.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class BackupWorker extends Worker {
 private int uploaded,downloaded,unchanged,excluded,conflicts,visited,filesChecked;
 private String current="Connecting";
 private String folderName="Sahl Sync";
 private void progress(){progress(0,0,false);}
 private void progress(long accepted,long total,boolean resumed){
  setProgressAsync(new Data.Builder()
   .putString("file",current.length()>500?current.substring(0,500)+"...":current)
   .putInt("filesChecked",filesChecked).putInt("uploaded",uploaded).putInt("downloaded",downloaded)
   .putInt("unchanged",unchanged).putInt("conflicts",conflicts)
   .putLong("acceptedBytes",accepted).putLong("totalBytes",total).putBoolean("resumed",resumed).build());
 }
 private final Set<String> seen=new HashSet<>();
 public BackupWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
 static Constraints constraints(JSONObject pair){return new Constraints.Builder().setRequiredNetworkType(pair.optBoolean("unmetered",true)?NetworkType.UNMETERED:NetworkType.CONNECTED).setRequiresCharging(pair.optBoolean("charging",false)).setRequiresBatteryNotLow(true).build();}
 static void enqueue(Context c,JSONObject pair,boolean periodic){
  String id=pair.optString("id");Data data=new Data.Builder().putString("pair",id).putBoolean("legacyDaily",periodic).build();
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
    JSONObject pair;
    synchronized(BackupSchedule.class){
     pair=Store.pair(c,getInputData().getString("pair"));
     String generation=getInputData().getString("scheduleGeneration");
     if(generation!=null){
      if(!generation.equals(pair.optString("scheduleGeneration")))return Result.success();
      int days=pair.optInt("scheduleDays");
      if(days!=0){
       long after=Math.max(System.currentTimeMillis(),getInputData().getLong("scheduleDue",0));
       long next=BackupSchedule.next(java.time.Instant.ofEpochMilli(after),java.time.ZoneId.systemDefault(),
        pair.getInt("scheduleHour"),pair.getInt("scheduleMinute"),days);
       BackupSchedule.enqueue(c,pair,next).getResult().get();
      }
      if(getRunAttemptCount()==0)Store.history(c,pair.optString("id"),"Scheduled backup started.");
     }else if(getInputData().getBoolean("legacyDaily",false)&&!pair.optBoolean("daily"))return Result.success();
    }
    folderName=pair.optString("name","Sahl Sync");
    if(!isScheduled())startManualForeground(c);
    BackupCancellation.check();
    Graph graph=Auth.graph(Auth.open(c),pair.getString("account"));
    DocumentFile local=DocumentFile.fromTreeUri(c,Uri.parse(pair.getString("local")));
    if(local==null||!local.isDirectory()||!local.canRead())throw new IOException("Local folder permission is unavailable; select it again");
    graph.enableResumableUploads(c,getInputData().getString("pair"),this::progress);
    if(SyncDirection.from(pair.optString("direction"))==SyncDirection.UPLOAD){
     walk(c,graph,local.getUri(),pair.getString("remote"),"",pair,0);
    }else{
     FolderSync sync=new FolderSync(c,graph,pair,this::reportFolderProgress);
     sync.run(Uri.parse(pair.getString("local")));
     reportFolderProgress(sync,current);
    }
    String counts=filesChecked+" files checked, "+(uploaded+downloaded)+" synced ("+uploaded+" uploaded, "
     +downloaded+" downloaded), "+unchanged+" unchanged";
    BackupCancellation.check();
    Store.history(c,getInputData().getString("pair"),(getInputData().getString("scheduleGeneration")!=null?"Scheduled backup finished: ":"Backup finished: ")+counts+", "+excluded+" excluded, "+conflicts+" conflicts.");
    notifyResult(counts+", "+conflicts+" conflicts.");
    if(conflicts==0)return Result.success(new Data.Builder().putString("summary","Completed: "+counts).build());
    return Result.failure(new Data.Builder().putString("summary",counts+", "+conflicts+" conflicts. See Backup activity.").build());
   }catch(Graph.HttpError e){
    if(isStopped())return Result.failure();
    boolean retry=e.status==429||e.status>=500;
    Store.history(c,getInputData().getString("pair"),current+": "+(retry&&getRunAttemptCount()<BackupRetry.MAX_RETRIES?"OneDrive is busy. Automatic retry queued.":"OneDrive HTTP "+e.status+". Check connection, sign-in, quota and destination access; then tap Back up now to retry."));
    if(retry&&getRunAttemptCount()<BackupRetry.MAX_RETRIES)return Result.retry();
    notifyResult("Could not finish. Open Backup activity to see details.");
    return Result.failure();
   }catch(UploadSession.ConnectionFailure e){
    boolean retry=!isStopped()&&getRunAttemptCount()<BackupRetry.MAX_RETRIES;
    Store.history(c,getInputData().getString("pair"),current+": "+(retry
     ?"Upload interrupted. Automatic retry queued."
     :"Upload interrupted. Tap Back up now to retry."));
    if(retry)return Result.retry();
    notifyResult("Upload interrupted. Open Backup activity to retry.");
    return Result.failure();
   }catch(MsalUiRequiredException|Auth.SignInRequired e){
    return signInRequired(c);
   }catch(MsalException e){
    String code=e.getErrorCode();
    String diagnostic=code!=null&&code.matches("[a-zA-Z0-9_]{1,48}")?" ("+code+")":"";
    if(BackupRetry.transientAuthCode(code)||BackupRetry.connectionFailure(e))
     return retryConnection(c,"OneDrive connection unavailable"+diagnostic+".");
    if(isStopped())return Result.failure();
    Store.history(c,getInputData().getString("pair"),"OneDrive connection failed"+diagnostic+".");
    notifyResult("Could not connect to OneDrive. See Backup activity.");
    return Result.failure();
   }catch(Exception e){
    if(BackupRetry.connectionFailure(e))
     return retryConnection(c,"Network interrupted while checking or uploading "+current+".");
    Store.history(c,getInputData().getString("pair"),isStopped()?"Backup stopped; completed uploads are kept.":current+": stopped after "+uploaded+" uploads. "+failureHint(e)+" Tap Back up now to retry; completed files are kept.");
    notifyResult("Could not finish. Open Backup activity to see details.");
    return Result.failure();
   }finally{BackupCancellation.clear();}
  }
 }
 private void reportFolderProgress(FolderSync sync,String path){
  current=path;
  filesChecked=sync.filesChecked;
  uploaded=sync.uploaded;
  downloaded=sync.downloaded;
  unchanged=sync.unchanged;
  excluded=sync.excluded;
  conflicts=sync.conflicts;
  progress();
 }

 private Result signInRequired(Context context){
  if(isStopped())return Result.failure();
  Store.history(context,getInputData().getString("pair"),"Microsoft sign-in required. Check your OneDrive account in Sahl Sync.");
  notifyResult("Microsoft sign-in required. Open Sahl Sync to check your account.");
  return Result.failure(new Data.Builder().putString("summary","Microsoft sign-in required. Connect this account and retry.").build());
 }

 private Result retryConnection(Context context,String reason){
  if(isStopped())return Result.failure();
  boolean retry=getRunAttemptCount()<BackupRetry.MAX_RETRIES;
  Store.history(context,getInputData().getString("pair"),reason
   +(retry?" Automatic retry queued.":" Retry limit reached. Check your connection and try again."));
  if(retry)return Result.retry();
  notifyResult("Network unavailable after retries. See Backup activity.");
  return Result.failure();
 }

 private boolean isScheduled(){
  Data input=getInputData();
  return input.getString("scheduleGeneration")!=null||input.getBoolean("legacyDaily",false);
 }

 private void startManualForeground(Context context)throws InterruptedException,ExecutionException{
  int notificationId=0x40000000|(getId().hashCode()&0x3fffffff);
  try{
   setForegroundAsync(BackupNotifications.running(context,notificationId,folderName)).get();
  }catch(ExecutionException error){
   // A constrained or retried job can start after the app has already gone into the background.
   // If Android declines foreground promotion, keep the scheduled work and its retry checkpoints.
   if(!(error.getCause() instanceof IllegalStateException))throw error;
   android.util.Log.w("SahlSync","Android declined foreground promotion for a manual backup.");
  }
 }

 private void notifyResult(String summary){
  if(isStopped())return;
  BackupNotifications.finished(getApplicationContext(),getInputData().getString("pair"),folderName,isScheduled(),summary);
 }

 private static String failureHint(Exception e){
  if(e instanceof IllegalArgumentException)return "Check unsupported file names.";
  return "Check folder access, free storage and the "+BackupRules.MAX_FILE_MIB+" MiB file limit.";
 }
 private void walk(Context c,Graph graph,Uri source,String remote,String path,JSONObject pair,int depth)throws Exception{
  if(depth>32||!seen.add(source.toString()))throw new IOException("Folder nesting limit reached");
  // Use the provider cursor directly: listFiles() can silently return an empty array on a failed query.
  Uri children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(source,android.provider.DocumentsContract.getDocumentId(source));
  try(android.database.Cursor cursor=c.getContentResolver().query(children,new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,android.provider.DocumentsContract.Document.COLUMN_SIZE},null,null,null)){
   if(cursor==null)throw new IOException("Cannot list local folder");
   while(cursor.moveToNext()){
    if(isStopped()||Thread.currentThread().isInterrupted())throw new InterruptedException();
    if(++visited>5000)throw new IOException("Alpha limit: 5000 items per run");
    String name=cursor.getString(1),relative=path+name;
    boolean directory=android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
    if(FolderSync.reserved(name)||BackupRules.excluded(relative,pair.optString("exclude"))){excluded++;continue;}
    if(!directory)filesChecked++;
    current=relative;progress();BackupRules.validateName(name);
    Uri uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(source,cursor.getString(0));
    if(directory){
     walk(c,graph,uri,graph.folder(remote,name),relative+"/",pair,depth+1);
    }else{
     try{
      BackupRules.requireFileSize(cursor.getLong(3));
      upload(c,graph,uri,remote,name,relative,pair);
     }catch(BackupRules.FileTooLarge error){
      excluded++;
      Store.history(c,getInputData().getString("pair"),relative+": skipped (over "+BackupRules.MAX_FILE_MIB+" MiB).");
     }
    }
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