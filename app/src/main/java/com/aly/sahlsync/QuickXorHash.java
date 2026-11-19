package com.aly.sahlsync;

import java.io.*;
import java.util.Base64;

/** Microsoft's QuickXorHash, the fingerprint OneDrive reports for every file. Comparing it avoids downloads. */
final class QuickXorHash {
 private static final int WIDTH=160,SHIFT=11;
 private final long[] data=new long[(WIDTH-1)/64+1];
 private long length;
 private int shift;

 void update(byte[] array,int offset,int count){
  int current=shift;
  int iterations=Math.min(count,WIDTH);
  for(int i=0;i<iterations;i++){
   boolean last=data.length-1==current/64;
   int index=current/64,bit=current%64;
   if(last){
    int width=WIDTH%64==0?64:WIDTH%64;
    for(int j=i;j<count;j+=WIDTH){
     long value=array[offset+j]&0xffL;
     data[index]^=value<<bit;
     if(bit>width-8)data[0]^=value>>>(width-bit);
    }
   }else{
    for(int j=i;j<count;j+=WIDTH){
     long value=array[offset+j]&0xffL;
     data[index]^=value<<bit;
     if(bit>56)data[index+1]^=value>>>(64-bit);
    }
   }
   current+=SHIFT;
   if(current>=WIDTH)current-=WIDTH;
  }
  shift=(int)((shift+(long)SHIFT*(count%WIDTH))%WIDTH);
  length+=count;
 }

 String digest(){
  byte[] result=new byte[(WIDTH-1)/8+1];
  for(int i=0;i<data.length;i++)
   for(int b=0;b<8&&i*8+b<result.length;b++)result[i*8+b]=(byte)(data[i]>>>(8*b));
  for(int b=0;b<8;b++)result[result.length-8+b]^=(byte)(length>>>(8*b));
  return Base64.getEncoder().encodeToString(result);
 }

 static String of(File file)throws Exception{
  QuickXorHash hash=new QuickXorHash();
  try(InputStream input=new FileInputStream(file)){
   byte[] buffer=new byte[65536];int count;
   while((count=input.read(buffer))!=-1){BackupCancellation.check();hash.update(buffer,0,count);}
  }
  return hash.digest();
 }
}
