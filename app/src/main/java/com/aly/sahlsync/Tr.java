package com.aly.sahlsync;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.*;

/** Arabic interface. Settings > Language: Automatic (phone language), English or العربية. */
final class Tr {
 private static final Map<String,String> AR=new HashMap<>();
 private static final String[][] PREFIX={
  {"Folder pair: ","المجلد: "},{"Phone folder: ","مجلد الهاتف: "},{"Direction: ","الاتجاه: "},{"Auto sync: ","مزامنة تلقائية: "},
  {"Last completed sync: ","آخر مزامنة: "},{"Installed version: ","الإصدار المثبت: "},{"You are up to date. Installed version: ","لديك أحدث إصدار: "},
  {"Account: ","الحساب: "},{"Checking phone files… ","جارٍ فحص ملفات الهاتف… "},{"Schedule: ","الجدول: "},{"Manual: ","يدوي: "},{"Next: ","التالي: "},{"Date: ","التاريخ: "},{"Time: ","الوقت: "},
  {"Daily: ","يوميًا: "},{"Running - ","تعمل الآن - "},{"Files checked so far: ","الملفات التي فُحصت: "},{"Change OneDrive folder (","تغيير مجلد OneDrive ("},
  {"OneDrive folder changed to ","تم تغيير مجلد OneDrive إلى "},{"Disconnect ","فصل "},{"Open this release in a browser: ","افتح هذا الإصدار في المتصفح: "},
 };
 static{
  String[] pairs={
   "Sahl Sync","سهل للمزامنة",
   "Keep your phone, OneDrive and PC in sync.","مزامنة هاتفك مع OneDrive وجهاز الكمبيوتر.",
   "Microsoft accounts","حسابات Microsoft","Connect OneDrive","ربط OneDrive","Disconnect an account","فصل حساب",
   "Background & notifications","العمل في الخلفية والإشعارات","Check for updates","التحقق من التحديثات","Checking for updates...","جارٍ التحقق من التحديثات...",
   "Sync with my PC (Wi-Fi)","المزامنة مع الكمبيوتر (Wi-Fi)","Sync with my PC","المزامنة مع الكمبيوتر",
   "Folder backups","مجلدات النسخ","Add folder backup","إضافة مجلد",
   "Tap a folder to sync it or change its settings. Replaced and deleted files are kept in Version history.","اضغط على مجلد لمزامنته أو تغيير إعداداته. الملفات المستبدلة والمحذوفة تُحفظ في سجل النسخ.",
   "Search folder backups","البحث في المجلدات","No matching folder backups.","لا توجد مجلدات مطابقة.",
   "No folders yet. Choose a folder on your phone, then a destination in OneDrive.","لا توجد مجلدات بعد. اختر مجلدًا على هاتفك ثم وجهته في OneDrive.",
   "Sync now","زامن الآن","Stop","إيقاف","Settings","الإعدادات","Activity","النشاط","Version history","سجل النسخ",
   "Refresh history","تحديث السجل","View full history","عرض السجل كاملًا","History","السجل","Backup history","سجل المزامنة",
   "Loading account...","جارٍ تحميل الحساب...","No Microsoft accounts connected","لا توجد حسابات Microsoft مرتبطة",
   "Microsoft sign-in setup is pending for this build.","تسجيل الدخول إلى Microsoft غير مهيأ في هذا الإصدار.",
   "Edit backup settings","إعدادات المجلد","Settings apply to future runs. A running backup keeps its current settings.","تُطبّق الإعدادات على المزامنات القادمة.",
   "Backup name","اسم المجلد","Enter a backup name","أدخل اسمًا","Exclusions, one pattern per line","استثناءات، نمط في كل سطر",
   "Exclusions, one pattern per line: *.tmp","استثناءات، نمط في كل سطر: *.tmp",
   "Unmetered network only","شبكة غير محدودة فقط (Wi-Fi)","Only while charging","أثناء الشحن فقط","Folders","المجلدات",
   "Change phone folder","تغيير مجلد الهاتف","Sync mode","وضع المزامنة",
   "Upload only (phone to OneDrive)","رفع فقط (من الهاتف إلى OneDrive)","Download only (OneDrive to phone)","تنزيل فقط (من OneDrive إلى الهاتف)",
   "Two-way (newest modified wins)","في الاتجاهين (الأحدث يفوز)","Two-way (newest wins)","في الاتجاهين (الأحدث يفوز)",
   "Phone to OneDrive","من الهاتف إلى OneDrive","OneDrive to phone","من OneDrive إلى الهاتف",
   "Sync deletions (two-way). Deleted files stay in Version history.","مزامنة الحذف (في الاتجاهين). الملفات المحذوفة تبقى في سجل النسخ.",
   "Sync deletions (two-way mode). Deleted files stay in Version history.","مزامنة الحذف (في الاتجاهين). الملفات المحذوفة تبقى في سجل النسخ.",
   "Older copies to keep per file","عدد النسخ القديمة لكل ملف","All (no cleanup)","الكل (بدون تنظيف)",
   "Notify me when this folder finishes syncing","أعلمني عند انتهاء مزامنة هذا المجلد",
   "Backup schedule","جدول المزامنة","Manual only","يدوي فقط","Once on a date","مرة واحدة في تاريخ","Every day","كل يوم","Selected weekdays","أيام محددة",
   "Every hour","كل ساعة","Every 6 hours","كل 6 ساعات","Every 15 minutes","كل 15 دقيقة",
   "Android may start the backup after the selected time, depending on battery and network conditions.","قد يبدأ أندرويد المزامنة بعد الوقت المحدد حسب البطارية والشبكة.",
   "Select at least one weekday.","اختر يومًا واحدًا على الأقل.","Pick a future date and time for the one-time backup.","اختر تاريخًا ووقتًا في المستقبل.",
   "Save","حفظ","Cancel","إلغاء","Close","إغلاق","Continue","متابعة","Later","لاحقًا","Not now","ليس الآن","Add","إضافة","Disconnect","فصل",
   "Use this folder","استخدم هذا المجلد","Back to OneDrive root","العودة إلى جذر OneDrive","Choose a OneDrive account","اختر حساب OneDrive",
   "Create folder backup","إنشاء مجلد مزامنة","Loading OneDrive folders...","جارٍ تحميل مجلدات OneDrive...","Connect OneDrive first.","اربط OneDrive أولًا.",
   "Cancel backup?","إيقاف المزامنة؟","Cancel backup","إيقاف المزامنة","Keep running","استمرار",
   "Waiting","في الانتظار","Sync is running.","المزامنة تعمل.","Preparing backup","جارٍ التحضير","Uploading","جارٍ الرفع",
   "Queued / waiting for network, battery or another backup","في الطابور / بانتظار الشبكة أو البطارية",
   "Scheduled / waiting for Android and selected conditions","مجدولة / بانتظار الشروط","Due / waiting for Android and selected conditions","مستحقة / بانتظار الشروط",
   "Cancelled / off. An accepted cloud request may still finish.","متوقفة.","Manual: ready","يدوي: جاهز","Schedule: off","الجدول: متوقف",
   "Allow background backups","السماح بالعمل في الخلفية","Allow background backups?","السماح بالعمل في الخلفية؟",
   "Notify when a backup finishes","إشعار عند انتهاء المزامنة","Pin a shortcut to Sahl Sync","تثبيت اختصار",
   "No older versions saved for this folder.","لا توجد نسخ قديمة لهذا المجلد.","Save a copy","حفظ نسخة","Save older version?","حفظ النسخة القديمة؟",
   "Older copy saved.","تم حفظ النسخة القديمة.","Version history - choose file","سجل النسخ - اختر ملفًا",
   "Open release","فتح الإصدار","No backup runs yet.","لا توجد مزامنات بعد.",
   "Add folder pair","إضافة مجلد","Folder pairs","المجلدات","Folder pair name","اسم المجلد","No PC paired","لم يتم الربط مع كمبيوتر",
   "PC pairing saved","تم الربط مع الكمبيوتر","Scan QR from PC","مسح رمز QR من الكمبيوتر","Enter pairing code","إدخال رمز الربط",
   "Pair with your PC","الربط مع الكمبيوتر","Pairing code from your PC","رمز الربط من الكمبيوتر","Choose phone folder","اختيار مجلد الهاتف",
   "No phone folder selected","لم يتم اختيار مجلد","Sync all folders","مزامنة كل المجلدات","Auto sync & options","المزامنة التلقائية والخيارات",
   "Options","الخيارات","Auto sync (on Wi-Fi, in the background)","المزامنة التلقائية (على Wi-Fi في الخلفية)","Auto sync only while charging","المزامنة التلقائية أثناء الشحن فقط",
   "Off","متوقفة","Phone → PC","الهاتف ← الكمبيوتر","PC → Phone","الكمبيوتر ← الهاتف",
   "Scan the QR shown in Sahl Sync on your PC","امسح رمز QR الظاهر في سهل للمزامنة على الكمبيوتر",
   "Looking for your PC on Wi-Fi…","جارٍ البحث عن الكمبيوتر على الشبكة…","Checking phone files…","جارٍ فحص ملفات الهاتف…","Checking PC files…","جارٍ فحص ملفات الكمبيوتر…",
   "Language","اللغة","An automatic sync is already running. Its progress is shown below.","توجد مزامنة تلقائية تعمل الآن. يظهر تقدّمها بالأسفل.","daily","يوميًا","Deletions sync","مزامنة الحذف","Deletions off","الحذف متوقف","every 15 min","كل 15 دقيقة","every 1 h","كل ساعة","every 6 h","كل 6 ساعات","Messages & apps backup","نسخ الرسائل والتطبيقات","Messages & apps","الرسائل والتطبيقات",
   "Back up your SMS, MMS, call log and app list. Choose a folder that Sahl Sync already syncs, so backups reach OneDrive or your PC automatically.","انسخ رسائلك وسجل المكالمات وقائمة تطبيقاتك. اختر مجلدًا يزامنه سهل، لتصل النسخ إلى OneDrive أو الكمبيوتر تلقائيًا.",
   "Backup folder","مجلد النسخ","Choose backup folder","اختيار مجلد النسخ","No folder chosen","لم يتم اختيار مجلد","What to back up","ماذا تنسخ",
   "SMS & MMS (with attachments)","الرسائل SMS و MMS (مع المرفقات)","Call log","سجل المكالمات","App list (to reinstall later)","قائمة التطبيقات (لإعادة تثبيتها لاحقًا)",
   "Also save APK files (bigger; for apps not from Play Store)","حفظ ملفات APK أيضًا (أكبر حجمًا؛ للتطبيقات من خارج المتجر)","Backups to keep","عدد النسخ المحفوظة",
   "Automatic backup","النسخ التلقائي","Every week","كل أسبوع","Every month","كل شهر","All","الكل","Back up now","انسخ الآن","Restore","استعادة",
   "Messages already on the phone are skipped, so restoring twice does not create duplicates.","الرسائل الموجودة على الهاتف تُتجاوز، فلا تتكرر عند الاستعادة مرتين.",
   "Restore messages & calls","استعادة الرسائل والمكالمات","Reinstall apps from a list","إعادة تثبيت التطبيقات من قائمة","SMS & MMS","الرسائل SMS و MMS",
   "One step from Android","خطوة مطلوبة من أندرويد","Android lets only the default SMS app restore messages. Sahl Sync will become the SMS app for the restore; afterwards it takes you to switch back to your messages app. SMS that arrive meanwhile are kept.","أندرويد يسمح لتطبيق الرسائل الافتراضي فقط باستعادة الرسائل. سيصبح سهل تطبيق الرسائل مؤقتًا، ثم ينقلك لإرجاع تطبيق رسائلك. الرسائل التي تصل خلال ذلك تُحفظ.",
   "Switch back to your messages app","أرجع تطبيق الرسائل الخاص بك","Restore is done. Choose your messages app as the default SMS app again","انتهت الاستعادة. اختر تطبيق رسائلك كتطبيق SMS الافتراضي مرة أخرى",
   "Open settings","فتح الإعدادات","Install","تثبيت","Next app","التطبيق التالي","Restoring…","جارٍ الاستعادة…","Last backup: ","آخر نسخة: ",
   "Choose a backup folder first.","اختر مجلد النسخ أولًا.","All apps in the list are already installed.","كل التطبيقات في القائمة مثبتة بالفعل.","Missing apps: ","تطبيقات غير مثبتة: ","Syncing with PC: ","المزامنة مع الكمبيوتر: ","Remove this folder","إزالة هذا المجلد","Remove this folder?","إزالة هذا المجلد؟","Remove","إزالة",
   "Sahl Sync stops syncing it. Your files on the phone and in OneDrive are not deleted.","سيتوقف سهل عن مزامنته. ملفاتك على الهاتف وفي OneDrive لن تُحذف.",
   "Sahl Sync stops syncing it with your PC. Your files on the phone and the PC are not deleted.","سيتوقف سهل عن مزامنته مع الكمبيوتر. ملفاتك على الهاتف والكمبيوتر لن تُحذف.","Starting…","جارٍ البدء…","Stopping after the current step…","جارٍ الإيقاف بعد الخطوة الحالية…",
   "Stopped. The next sync continues where it left off.","تم الإيقاف. المزامنة القادمة تكمل من حيث توقفت.","Automatic (phone language)","تلقائي (لغة الهاتف)",
   "Could not save options.","تعذّر حفظ الخيارات.","Could not load Wi-Fi folder pairs.","تعذّر تحميل المجلدات.",
   "Enter the full pairing code shown on your PC.","أدخل رمز الربط كاملًا كما يظهر على الكمبيوتر.","Could not verify the PC. Check Wi-Fi and the pairing code.","تعذّر التحقق من الكمبيوتر. تحقق من الشبكة ورمز الربط.",
  };
  for(int i=0;i<pairs.length;i+=2)AR.put(pairs[i],pairs[i+1]);
 }

 private static Boolean arabic;
 static boolean arabic(Context c){
  if(arabic==null){
   String mode=c.getSharedPreferences("sync",Context.MODE_PRIVATE).getString("language","auto");
   arabic=mode.equals("ar")||mode.equals("auto")&&"ar".equals(Locale.getDefault().getLanguage());
  }
  return arabic;
 }
 static void setLanguage(Context c,String mode){
  c.getSharedPreferences("sync",Context.MODE_PRIVATE).edit().putString("language",mode).apply();
  arabic=null;
 }
 static String language(Context c){return c.getSharedPreferences("sync",Context.MODE_PRIVATE).getString("language","auto");}

 static String[] all(Context c,String[] values){
  String[] out=new String[values.length];for(int i=0;i<values.length;i++)out[i]=t(c,values[i]);return out;
 }

 static String t(Context c,CharSequence value){
  if(value==null)return null;
  String text=value.toString();
  if(!arabic(c))return text;
  String exact=AR.get(text);
  if(exact!=null)return exact;
  for(String[] p:PREFIX)if(text.startsWith(p[0]))return p[1]+t(c,text.substring(p[0].length()));
  for(String[] p:PREFIX)if(text.startsWith(p[0].trim()))return text;
  return text;
 }

 /** Translates, applies the Sahl font and right-to-left layout to a whole screen or dialog. */
 static void tree(Context c,View view){
  if(view==null)return;
  boolean ar=arabic(c);
  if(view instanceof TextView){
   TextView text=(TextView)view;
   if(!(view instanceof android.widget.EditText)){
    String translated=t(c,text.getText());
    if(translated!=null&&!translated.contentEquals(text.getText()))text.setText(translated);
   }
   CharSequence hint=text.getHint();
   if(hint!=null){String h=t(c,hint);if(!h.contentEquals(hint))text.setHint(h);}
   Fonts.apply(c,text);
  }
  if(ar)view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
  if(view instanceof ViewGroup){
   ViewGroup group=(ViewGroup)view;
   for(int i=0;i<group.getChildCount();i++)tree(c,group.getChildAt(i));
  }
 }

 /** AlertDialog.Builder that translates its texts and styles the dialog when shown. */
 static AlertDialog.Builder builder(Context c){
  return new AlertDialog.Builder(c){
   @Override public AlertDialog.Builder setTitle(CharSequence title){return super.setTitle(t(c,title));}
   @Override public AlertDialog.Builder setMessage(CharSequence message){return super.setMessage(t(c,message));}
   @Override public AlertDialog.Builder setPositiveButton(CharSequence text,DialogInterface.OnClickListener l){return super.setPositiveButton(t(c,text),l);}
   @Override public AlertDialog.Builder setNegativeButton(CharSequence text,DialogInterface.OnClickListener l){return super.setNegativeButton(t(c,text),l);}
   @Override public AlertDialog.Builder setNeutralButton(CharSequence text,DialogInterface.OnClickListener l){return super.setNeutralButton(t(c,text),l);}
   @Override public AlertDialog.Builder setItems(CharSequence[] items,DialogInterface.OnClickListener l){
    CharSequence[] copy=new CharSequence[items.length];for(int i=0;i<items.length;i++)copy[i]=t(c,items[i]);
    return super.setItems(copy,l);
   }
   @Override public AlertDialog create(){
    AlertDialog dialog=super.create();
    if(dialog.getWindow()!=null){
     View decor=dialog.getWindow().getDecorView();
     decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
      @Override public void onViewAttachedToWindow(View v){tree(c,v);}
      @Override public void onViewDetachedFromWindow(View v){}
     });
    }
    return dialog;
   }
  };
 }
}
