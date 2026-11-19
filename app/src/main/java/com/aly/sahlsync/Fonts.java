package com.aly.sahlsync;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;
import java.io.*;

/**
 * Thmanyah Sans for the Arabic interface. Its licence allows it inside apps but not sharing the font files, so the
 * repository holds only an encrypted copy (fonts-locked/), the build packs it scrambled into assets/f/, and it is
 * unpacked here into the app's private folder. Builds without the FONT_KEY secret simply use the phone's font.
 */
final class Fonts {
 private static final String KEY="Sahl-Sync/thmanyah-font/scramble-v1";   // must match tools/pack_fonts.py
 private static Typeface regular,bold;
 private static boolean loaded;

 private static synchronized void load(Context c){
  if(loaded)return;
  loaded=true;
  regular=unpack(c,"r");
  bold=unpack(c,"b");
 }

 private static Typeface unpack(Context c,String name){
  File out=new File(c.getNoBackupFilesDir(),"f-"+name+".otf");
  try{
   if(!out.isFile()||out.length()==0){
    try(InputStream in=c.getAssets().open("f/"+name+".bin");OutputStream o=new FileOutputStream(out)){
     byte[] buffer=new byte[65536];int n;long index=0;
     while((n=in.read(buffer))!=-1){
      for(int i=0;i<n;i++,index++)buffer[i]^=(byte)KEY.charAt((int)(index%KEY.length()));
      o.write(buffer,0,n);
     }
    }
   }
   return Typeface.createFromFile(out);
  }catch(Exception missing){
   out.delete();
   return null;   // Build without the font: keep the phone's font.
  }
 }

 static void apply(Context c,TextView view){
  if(!Tr.arabic(c))return;
  load(c);
  Typeface current=view.getTypeface();
  boolean isBold=current!=null&&current.isBold();
  Typeface face=isBold&&bold!=null?bold:regular;
  if(face!=null&&current!=face)view.setTypeface(face);
 }
}
