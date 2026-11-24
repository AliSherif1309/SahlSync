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
 private int uploaded,downloaded,deleted,unchanged,excluded,conflicts,filesChecked;
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
      }else if(pair.optLong("scheduleInterval")>0){
       BackupSchedule.enqueue(c,pair,System.currentTimeMillis()+pair.getLong("scheduleInterval")).getResult().get();
      }
      if(getRunAttemptCount()==0)Store.history(c,pair.optString("id"),"Scheduled backup started.");
     }else if(getInputData().getBoolean("legacyDaily",false)&&!pair.optBoolean("daily"))return Result.success();
    }
    SyncPair syncPair=SyncPair.fromJson(pair);
    folderName=syncPair.name.isEmpty()?"Sahl Sync":syncPair.name;
    notifyEnabled=syncPair.notify;
    if(!isScheduled())startManualForeground(c);
    BackupCancellation.check();
    CloudDrive drive=openDrive(c,syncPair);
    DocumentFile local=DocumentFile.fromTreeUri(c,Uri.parse(syncPair.local));
    if(local==null||!local.isDirectory()||!local.canRead())throw new IOException("Local folder permission is unavailable; select it again");
    FolderSync sync=new FolderSync(c,drive,syncPair,this::reportFolderProgress);
    sync.run(local.getUri());
    reportFolderProgress(sync,current);
    String counts=filesChecked+" files checked, "+(uploaded+downloaded)+" synced ("+uploaded+" uploaded, "
     +downloaded+" downloaded), "+(deleted>0?deleted+" deleted, ":"")+unchanged+" unchanged";
    BackupCancellation.check();
    Store.history(c,getInputData().getString("pair"),(getInputData().getString("scheduleGeneration")!=null?"Scheduled backup finished: ":"Backup finished: ")+counts+", "+excluded+" excluded, "+conflicts+" conflicts.");
    notifyResult(counts+", "+conflicts+" conflicts.");
    if(conflicts==0)return Result.success(new Data.Builder().putString("summary","Completed: "+counts).build());
    return Result.failure(new Data.Builder().putString("summary",counts+", "+conflicts+" conflicts. See Backup activity.").build());
   }catch(CloudDrive.HttpError e){
    if(isStopped())return Result.failure();
    Store.history(c,getInputData().getString("pair"),current+": "+(e.retryable&&getRunAttemptCount()<BackupRetry.MAX_RETRIES?"Cloud service is busy. Automatic retry queued.":e.getMessage()+". Check connection, sign-in, quota and destination access; then tap Back up now to retry."));
    if(e.retryable&&getRunAttemptCount()<BackupRetry.MAX_RETRIES)return Result.retry();
    notifyResult("Could not finish. Open Backup activity to see details.");
    return Result.failure();
   }catch(CloudDrive.ConnectionInterrupted e){
    boolean retry=!isStopped()&&getRunAttemptCount()<BackupRetry.MAX_RETRIES;
    Store.history(c,getInputData().getString("pair"),current+": "+(retry
     ?"Upload interrupted. Automatic retry queued."
     :"Upload interrupted. Tap Back up now to retry."));
    if(retry)return Result.retry();
    notifyResult("Upload interrupted. Open Backup activity to retry.");
    return Result.failure();
   }catch(CloudDrive.AuthRequired|MsalUiRequiredException|Auth.SignInRequired e){
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
  deleted=sync.deleted;
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

 private boolean notifyEnabled=true;
 private void notifyResult(String summary){
  if(isStopped()||!notifyEnabled)return;
  BackupNotifications.finished(getApplicationContext(),getInputData().getString("pair"),folderName,isScheduled(),summary);
 }

 private CloudDrive openDrive(Context c,SyncPair pair)throws Exception{
  if(pair.provider==SyncProvider.GOOGLE_DRIVE)
   throw new IOException("Google Drive is not configured in this build yet.");
  Graph graph=Auth.graph(Auth.open(c),pair.account);
  graph.enableResumableUploads(c,pair.id,this::progress);
  return new OneDriveDrive(graph);
 }

 private static String failureHint(Exception e){
  if(e instanceof IllegalArgumentException)return "Check unsupported file names.";
  return "Check folder access, free storage and the "+BackupRules.MAX_FILE_MIB+" MiB file limit.";
 }
}