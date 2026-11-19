package com.aly.sahlsync;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.Telephony;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.json.*;

/**
 * Messages, call log and app list backup (replaces "SMS Import/Export" and "App List Backup").
 * Messages backup = one zip: messages.ndjson (one SMS/MMS per line, all provider columns), calls.ndjson,
 * and data/ (MMS attachments). SMS lines are plain column maps, so SMS from similar backup apps import too.
 */
final class PhoneData {
 static final String MESSAGES_PREFIX="sahl-messages-",APPS_PREFIX="sahl-apps-";
 private static final String[] SMS_COLUMNS={"address","body","date","date_sent","read","seen","status","type","subject",
  "service_center","protocol","locked","error_code","reply_path_present"};
 private static final String[] MMS_COLUMNS={"date","date_sent","msg_box","read","seen","sub","sub_cs","ct_t","ct_l","m_type",
  "m_id","tr_id","exp","pri","rr","rpt_a","read_status","locked","m_size","m_cls","v","resp_st","st","d_rpt","text_only"};
 private static final String[] PART_COLUMNS={"seq","ct","name","chset","cd","fn","cid","cl","ctt_s","ctt_t","text"};
 private static final String[] CALL_COLUMNS={CallLog.Calls.NUMBER,CallLog.Calls.DATE,CallLog.Calls.DURATION,CallLog.Calls.TYPE,
  CallLog.Calls.NEW,CallLog.Calls.CACHED_NAME,CallLog.Calls.NUMBER_PRESENTATION,CallLog.Calls.COUNTRY_ISO,CallLog.Calls.GEOCODED_LOCATION,
  CallLog.Calls.IS_READ,CallLog.Calls.FEATURES};

 private final Context context;
 private final ContentResolver resolver;
 private final Consumer<String> progress;

 PhoneData(Context context,Consumer<String> progress){
  this.context=context;this.resolver=context.getContentResolver();this.progress=progress;
 }

 static String stamp(){return new SimpleDateFormat("yyyy-MM-dd_HH-mm",Locale.US).format(new Date());}

 // ---------------------------------------------------------------- export: messages + calls

 String exportMessages(DocumentFile folder,boolean sms,boolean calls)throws Exception{
  DocumentFile out=folder.createFile("application/zip",MESSAGES_PREFIX+stamp()+".zip");
  if(out==null)throw new IOException("Could not create the backup file in the chosen folder.");
  int smsCount=0,mmsCount=0,callCount=0;
  List<Long> attachments=new ArrayList<>();
  try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(resolver.openOutputStream(out.getUri())))){
   if(sms){
    zip.putNextEntry(new ZipEntry("messages.ndjson"));
    Writer lines=new OutputStreamWriter(zip,StandardCharsets.UTF_8);
    try(Cursor c=resolver.query(Telephony.Sms.CONTENT_URI,null,null,null,"date")){
     while(c!=null&&c.moveToNext()){
      BackupCancellation.check();
      lines.write(row(c).toString());lines.write('\n');
      if(++smsCount%200==0)progress.accept("SMS: "+smsCount);
     }
    }
    try(Cursor c=resolver.query(Telephony.Mms.CONTENT_URI,null,null,null,"date")){
     while(c!=null&&c.moveToNext()){
      BackupCancellation.check();
      JSONObject mms=row(c);
      long id=c.getLong(c.getColumnIndexOrThrow("_id"));
      JSONArray addresses=new JSONArray();
      try(Cursor a=resolver.query(Uri.parse("content://mms/"+id+"/addr"),null,null,null,null)){
       while(a!=null&&a.moveToNext())addresses.put(row(a));
      }
      JSONArray parts=new JSONArray();
      try(Cursor p=resolver.query(Uri.parse("content://mms/part"),null,"mid=?",new String[]{String.valueOf(id)},"seq")){
       while(p!=null&&p.moveToNext()){
        JSONObject part=row(p);
        if(!p.isNull(p.getColumnIndexOrThrow("_data"))){
         long partId=p.getLong(p.getColumnIndexOrThrow("_id"));
         attachments.add(partId);
         part.put("__file","data/"+partId);
        }
        parts.put(part);
       }
      }
      mms.put("__mms",true).put("__addresses",addresses).put("__parts",parts);
      lines.write(mms.toString());lines.write('\n');
      if(++mmsCount%50==0)progress.accept("MMS: "+mmsCount);
     }
    }
    lines.flush();zip.closeEntry();
    byte[] buffer=new byte[65536];
    for(long partId:attachments){
     BackupCancellation.check();
     try(InputStream in=resolver.openInputStream(Uri.parse("content://mms/part/"+partId))){
      if(in==null)continue;
      zip.putNextEntry(new ZipEntry("data/"+partId));
      int n;while((n=in.read(buffer))!=-1)zip.write(buffer,0,n);
      zip.closeEntry();
     }catch(FileNotFoundException missing){/* Attachment no longer on the phone. */}
    }
   }
   if(calls){
    zip.putNextEntry(new ZipEntry("calls.ndjson"));
    Writer lines=new OutputStreamWriter(zip,StandardCharsets.UTF_8);
    try(Cursor c=resolver.query(CallLog.Calls.CONTENT_URI,null,null,null,CallLog.Calls.DATE)){
     while(c!=null&&c.moveToNext()){BackupCancellation.check();lines.write(row(c).toString());lines.write('\n');callCount++;}
    }
    lines.flush();zip.closeEntry();
   }
   zip.putNextEntry(new ZipEntry("info.json"));
   zip.write(new JSONObject().put("app","Sahl Sync").put("created",System.currentTimeMillis()).put("device",Build.MODEL)
    .put("sms",smsCount).put("mms",mmsCount).put("calls",callCount).toString(1).getBytes(StandardCharsets.UTF_8));
   zip.closeEntry();
  }catch(Exception error){out.delete();throw error;}
  return smsCount+" SMS, "+mmsCount+" MMS, "+callCount+" calls saved to "+out.getName();
 }

 private static JSONObject row(Cursor c)throws JSONException{
  JSONObject value=new JSONObject();
  for(int i=0;i<c.getColumnCount();i++){
   switch(c.getType(i)){
    case Cursor.FIELD_TYPE_INTEGER: value.put(c.getColumnName(i),c.getLong(i));break;
    case Cursor.FIELD_TYPE_FLOAT: value.put(c.getColumnName(i),c.getDouble(i));break;
    case Cursor.FIELD_TYPE_STRING: value.put(c.getColumnName(i),c.getString(i));break;
    default: break;
   }
  }
  return value;
 }

 // ---------------------------------------------------------------- import: messages + calls

 /** Needs Sahl Sync to be the default SMS app (Android rule) for messages; calls only need the call-log permission. */
 String importMessages(Uri backup,boolean messages,boolean calls)throws Exception{
  File work=new File(context.getCacheDir(),"restore");
  deleteTree(work);work.mkdirs();
  try{
   unzip(backup,work);
   int sms=0,mms=0,callCount=0,skipped=0;
   File list=new File(work,"messages.ndjson");
   if(messages&&list.isFile()){
    Set<String> existing=existingSms();
    Set<String> existingMms=existingMms();
    List<ContentValues> batch=new ArrayList<>();
    try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(list),StandardCharsets.UTF_8))){
     String line;
     while((line=in.readLine())!=null){
      BackupCancellation.check();
      if(line.trim().isEmpty())continue;
      JSONObject item=new JSONObject(line);
      if(item.optBoolean("__mms")||item.has("__parts")){
       if(existingMms.add(item.optLong("date")+"|"+item.optString("m_id"))){if(insertMms(item,work))mms++;}else skipped++;
       continue;
      }
      String key=item.optLong("date")+"|"+item.optString("address")+"|"+item.optString("body").hashCode();
      if(!existing.add(key)){skipped++;continue;}
      batch.add(values(item,SMS_COLUMNS));
      if(batch.size()>=200){sms+=flush(Telephony.Sms.CONTENT_URI,batch);progress.accept("SMS restored: "+sms);}
     }
    }
    sms+=flush(Telephony.Sms.CONTENT_URI,batch);
   }
   File callList=new File(work,"calls.ndjson");
   if(calls&&callList.isFile()){
    Set<String> existing=new HashSet<>();
    try(Cursor c=resolver.query(CallLog.Calls.CONTENT_URI,new String[]{CallLog.Calls.DATE,CallLog.Calls.NUMBER},null,null,null)){
     while(c!=null&&c.moveToNext())existing.add(c.getLong(0)+"|"+c.getString(1));
    }
    List<ContentValues> batch=new ArrayList<>();
    try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(callList),StandardCharsets.UTF_8))){
     String line;
     while((line=in.readLine())!=null){
      BackupCancellation.check();
      if(line.trim().isEmpty())continue;
      JSONObject item=new JSONObject(line);
      if(!existing.add(item.optLong("date")+"|"+item.optString("number"))){skipped++;continue;}
      batch.add(values(item,CALL_COLUMNS));
      if(batch.size()>=200)callCount+=flush(CallLog.Calls.CONTENT_URI,batch);
     }
    }
    callCount+=flush(CallLog.Calls.CONTENT_URI,batch);
   }
   return sms+" SMS, "+mms+" MMS and "+callCount+" calls restored"+(skipped>0?" ("+skipped+" already on the phone, skipped)":"")+".";
  }finally{deleteTree(work);}
 }

 private int flush(Uri target,List<ContentValues> batch){
  if(batch.isEmpty())return 0;
  int count=resolver.bulkInsert(target,batch.toArray(new ContentValues[0]));
  batch.clear();
  return count;
 }

 private Set<String> existingSms(){
  Set<String> keys=new HashSet<>();
  try(Cursor c=resolver.query(Telephony.Sms.CONTENT_URI,new String[]{"date","address","body"},null,null,null)){
   while(c!=null&&c.moveToNext())keys.add(c.getLong(0)+"|"+c.getString(1)+"|"+String.valueOf(c.getString(2)).hashCode());
  }
  return keys;
 }

 private Set<String> existingMms(){
  Set<String> keys=new HashSet<>();
  try(Cursor c=resolver.query(Telephony.Mms.CONTENT_URI,new String[]{"date","m_id"},null,null,null)){
   while(c!=null&&c.moveToNext())keys.add(c.getLong(0)+"|"+c.getString(1));
  }
  return keys;
 }

 private boolean insertMms(JSONObject item,File work)throws Exception{
  JSONArray addresses=item.optJSONArray("__addresses");
  Set<String> recipients=new HashSet<>();
  if(addresses!=null)for(int i=0;i<addresses.length();i++){
   String address=addresses.getJSONObject(i).optString("address");
   if(!address.isEmpty()&&!address.equals("insert-address-token"))recipients.add(address);
  }
  if(recipients.isEmpty())return false;
  ContentValues values=values(item,MMS_COLUMNS);
  values.put("thread_id",Telephony.Threads.getOrCreateThreadId(context,recipients));
  Uri message=resolver.insert(Telephony.Mms.CONTENT_URI,values);
  if(message==null)return false;
  String id=message.getLastPathSegment();
  if(addresses!=null)for(int i=0;i<addresses.length();i++){
   JSONObject address=addresses.getJSONObject(i);
   ContentValues a=values(address,new String[]{"address","type","charset"});
   a.put("msg_id",id);
   resolver.insert(Uri.parse("content://mms/"+id+"/addr"),a);
  }
  JSONArray parts=item.optJSONArray("__parts");
  if(parts!=null)for(int i=0;i<parts.length();i++){
   JSONObject part=parts.getJSONObject(i);
   ContentValues p=values(part,PART_COLUMNS);
   p.put("mid",id);
   Uri inserted=resolver.insert(Uri.parse("content://mms/"+id+"/part"),p);
   String file=part.optString("__file");
   if(inserted!=null&&!file.isEmpty()){
    File data=new File(work,file);
    if(data.isFile())try(InputStream in=new FileInputStream(data);OutputStream out=resolver.openOutputStream(inserted)){
     if(out!=null){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
    }
   }
  }
  return true;
 }

 private static ContentValues values(JSONObject item,String[] columns){
  ContentValues values=new ContentValues();
  for(String column:columns){
   if(!item.has(column)||item.isNull(column))continue;
   Object value=item.opt(column);
   if(value instanceof Number)values.put(column,((Number)value).longValue());
   else values.put(column,String.valueOf(value));
  }
  return values;
 }

 private void unzip(Uri backup,File target)throws IOException{
  String root=target.getCanonicalPath()+File.separator;
  try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(resolver.openInputStream(backup)))){
   ZipEntry entry;byte[] buffer=new byte[65536];
   while((entry=zip.getNextEntry())!=null){
    File file=new File(target,entry.getName());
    if(!file.getCanonicalPath().startsWith(root))throw new IOException("Unsafe backup file.");
    if(entry.isDirectory()){file.mkdirs();continue;}
    file.getParentFile().mkdirs();
    try(OutputStream out=new FileOutputStream(file)){int n;while((n=zip.read(buffer))!=-1)out.write(buffer,0,n);}
   }
  }
 }

 private static void deleteTree(File file){
  File[] children=file.listFiles();
  if(children!=null)for(File child:children)deleteTree(child);
  file.delete();
 }

 // ---------------------------------------------------------------- app list

 static final class App {
  final String name,packageName,version,installer;final boolean installed;
  App(String name,String packageName,String version,String installer,boolean installed){
   this.name=name;this.packageName=packageName;this.version=version;this.installer=installer;this.installed=installed;
  }
 }

 List<App> installedApps(){
  PackageManager pm=context.getPackageManager();
  List<App> apps=new ArrayList<>();
  for(PackageInfo info:pm.getInstalledPackages(0)){
   ApplicationInfo app=info.applicationInfo;
   if(app==null||(app.flags&ApplicationInfo.FLAG_SYSTEM)!=0&&(app.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)==0)continue;
   if(info.packageName.equals(context.getPackageName()))continue;
   String installer=null;
   try{
    installer=Build.VERSION.SDK_INT>=30?pm.getInstallSourceInfo(info.packageName).getInstallingPackageName():pm.getInstallerPackageName(info.packageName);
   }catch(Exception ignored){}
   apps.add(new App(String.valueOf(app.loadLabel(pm)),info.packageName,info.versionName==null?"":info.versionName,installer==null?"":installer,true));
  }
  apps.sort((a,b)->a.name.compareToIgnoreCase(b.name));
  return apps;
 }

 String exportApps(DocumentFile folder,boolean apks)throws Exception{
  List<App> apps=installedApps();
  String stamp=stamp();
  JSONArray list=new JSONArray();
  StringBuilder html=new StringBuilder("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
   +"<title>My apps</title><style>body{font-family:sans-serif;background:#0E1A24;color:#EEF3F1;padding:16px}"
   +"a{color:#E3B85C}li{margin:8px 0}small{color:#8FA39C}</style><h2>My apps ("+apps.size()+") - "+stamp+"</h2><ol>");
  for(App app:apps){
   list.put(new JSONObject().put("name",app.name).put("package",app.packageName).put("version",app.version).put("installer",app.installer));
   html.append("<li><a href='https://play.google.com/store/apps/details?id=").append(app.packageName).append("'>")
    .append(escape(app.name)).append("</a> <small>").append(app.packageName).append(' ').append(escape(app.version)).append("</small>");
  }
  html.append("</ol>");
  write(folder,"application/json",APPS_PREFIX+stamp+".json",new JSONObject().put("app","Sahl Sync").put("created",System.currentTimeMillis())
   .put("device",Build.MODEL).put("apps",list).toString(1));
  write(folder,"text/html",APPS_PREFIX+stamp+".html",html.toString());
  int saved=0;
  if(apks){
   DocumentFile apkFolder=folder.findFile("APKs");
   if(apkFolder==null)apkFolder=folder.createDirectory("APKs");
   PackageManager pm=context.getPackageManager();
   for(App app:apps){
    BackupCancellation.check();
    progress.accept("Saving APK: "+app.name);
    ApplicationInfo info=pm.getApplicationInfo(app.packageName,0);
    String base=safe(app.name)+"_"+safe(app.version);
    if(apkFolder==null)break;
    DocumentFile old=apkFolder.findFile(base+(info.splitSourceDirs==null?".apk":".apks"));
    if(old!=null){saved++;continue;}   // Same version already saved.
    if(info.splitSourceDirs==null){
     DocumentFile out=apkFolder.createFile("application/vnd.android.package-archive",base+".apk");
     if(out!=null)try(InputStream in=new FileInputStream(info.sourceDir);OutputStream o=resolver.openOutputStream(out.getUri())){copy(in,o);saved++;}
    }else{
     DocumentFile out=apkFolder.createFile("application/octet-stream",base+".apks");
     if(out!=null)try(ZipOutputStream zip=new ZipOutputStream(resolver.openOutputStream(out.getUri()))){
      List<String> files=new ArrayList<>();files.add(info.sourceDir);files.addAll(Arrays.asList(info.splitSourceDirs));
      for(String path:files){zip.putNextEntry(new ZipEntry(new File(path).getName()));try(InputStream in=new FileInputStream(path)){copy(in,zip);}zip.closeEntry();}
      saved++;
     }
    }
   }
  }
  return apps.size()+" apps listed"+(apks?", "+saved+" APK files saved":"")+".";
 }

 /** Reads a saved app list and marks which apps are not installed on this phone. */
 List<App> readAppList(Uri file)throws Exception{
  StringBuilder text=new StringBuilder();
  try(BufferedReader in=new BufferedReader(new InputStreamReader(resolver.openInputStream(file),StandardCharsets.UTF_8))){
   String line;while((line=in.readLine())!=null)text.append(line).append('\n');
  }
  JSONArray list=new JSONObject(text.toString()).getJSONArray("apps");
  PackageManager pm=context.getPackageManager();
  List<App> apps=new ArrayList<>();
  for(int i=0;i<list.length();i++){
   JSONObject a=list.getJSONObject(i);
   boolean installed;
   try{pm.getPackageInfo(a.getString("package"),0);installed=true;}catch(PackageManager.NameNotFoundException e){installed=false;}
   apps.add(new App(a.optString("name"),a.getString("package"),a.optString("version"),a.optString("installer"),installed));
  }
  return apps;
 }

 private void write(DocumentFile folder,String mime,String name,String text)throws IOException{
  DocumentFile out=folder.createFile(mime,name);
  if(out==null)throw new IOException("Could not create "+name);
  try(OutputStream o=resolver.openOutputStream(out.getUri())){o.write(text.getBytes(StandardCharsets.UTF_8));}
 }

 /** Keeps only the newest backups of one kind. */
 static void prune(DocumentFile folder,String prefix,int keep){
  if(keep<=0)return;
  List<DocumentFile> files=new ArrayList<>();
  for(DocumentFile f:folder.listFiles())if(f.isFile()&&f.getName()!=null&&f.getName().startsWith(prefix))files.add(f);
  files.sort((a,b)->String.valueOf(b.getName()).compareTo(String.valueOf(a.getName())));
  Set<String> kept=new HashSet<>();
  for(DocumentFile f:files){
   String name=f.getName();String stamp=name.substring(prefix.length(),Math.min(name.length(),prefix.length()+16));
   if(kept.size()<keep||kept.contains(stamp))kept.add(stamp);else f.delete();
  }
 }

 private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
 private static String safe(String s){return s.replaceAll("[\\\\/:*?\"<>|]","_");}
 private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;");}
}
