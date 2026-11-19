package com.aly.sahlsync;

import android.content.Context;
import android.net.Uri;
import java.util.function.Consumer;
import javax.net.ssl.SSLHandshakeException;

/** Runs one Wi-Fi folder pair. Shared by the screen and the background worker. */
final class LanRunner {
 static String archiveKey(LanPairs.Pair item,LanPeer peer){
  if(item.legacyArchive)return peer.identity;
  return item.archiveKey.isEmpty()?item.id:item.archiveKey;
 }

 /** Syncs and records the result on the pair. If the PC moved to a new address, finds it on Wi-Fi first. */
 static void run(Context context,LanPairs pairs,LanPairs.Pair item,Consumer<String> progress){
  try{
   try{
    sync(context,item,progress);
   }catch(java.net.ConnectException|java.net.SocketTimeoutException|java.net.NoRouteToHostException unreachable){
    progress.accept("Looking for your PC on Wi-Fi…");
    String found=LanPeer.discover(item.code);
    if(found==null||found.equals(item.code))throw unreachable;
    item.code=found;
    sync(context,item,progress);
   }
   item.lastCompleted=System.currentTimeMillis();
  }catch(SSLHandshakeException error){
   item.result="Could not verify the PC identity. Pair again with the QR code.";
  }catch(java.net.ConnectException|java.net.SocketTimeoutException|java.net.NoRouteToHostException error){
   item.result="Could not reach the PC. Make sure Sahl Sync for PC is sharing and both are on the same Wi-Fi.";
  }catch(Exception error){
   item.result=error.getMessage();
   if(item.result==null||item.result.isEmpty())item.result="Wi-Fi sync could not finish.";
  }
  try{pairs.update(item);}catch(Exception ignored){/* The screen shows the in-memory result. */}
 }

 private static void sync(Context context,LanPairs.Pair item,Consumer<String> progress)throws Exception{
  LanPeer peer=new LanPeer(item.code);
  item.result=new LanFolderSync(context,peer,LanFolderSync.Mode.from(item.mode),archiveKey(item,peer),progress)
   .deletions(item.deletions).run(Uri.parse(item.tree));
 }
}
