package com.aly.sahlsync;

import java.io.*;

/** Provider-independent archive-before-update contract, shared by future sync backends. */
final class VersionedBackup {
 enum Outcome { CREATED, UPDATED, UNCHANGED, CONFLICT }
 static final class Item {
  final String id,tag;final long size;
  Item(String id,String tag,long size){this.id=id;this.tag=tag;this.size=size;}
 }
 static final class Receipt {
  final String id,tag,hash;final long size;
  Receipt(String id,String tag,String hash,long size){this.id=id;this.tag=tag;this.hash=hash;this.size=size;}
 }
 interface Backend {
  Item find(String name)throws Exception;
  default void reportConflict(Item remote,long localSize) throws Exception {}
  String hash(Item item)throws Exception;
  Item create(String name,File source)throws Exception;
  // Must store and verify an independent immutable copy before returning.
  void archive(Item item,String expectedHash)throws Exception;
  // Must reject if the provider's current tag differs from expected.tag.
  Item update(Item expected,File source)throws Exception;
 }
 static final class Result {
  final Outcome outcome;final Receipt receipt;
  Result(Outcome outcome,Receipt receipt){this.outcome=outcome;this.receipt=receipt;}
 }
 static Result backup(Backend backend,String name,File source,String localHash,Receipt known)throws Exception{
  BackupRules.validateName(name);
  Item remote=backend.find(name);
  if(remote==null){Item created=backend.create(name,source);return result(Outcome.CREATED,created,localHash);}
  String remoteHash=known!=null&&known.id.equals(remote.id)&&known.tag!=null&&!known.tag.isEmpty()&&known.tag.equals(remote.tag)&&known.size==remote.size
   ?known.hash:backend.hash(remote);
  if(remote.size==source.length()&&localHash.equals(remoteHash))return result(Outcome.UNCHANGED,remote,localHash);
  if(known==null||!known.id.equals(remote.id)||!known.hash.equals(remoteHash)){
   backend.reportConflict(remote,source.length());
   return new Result(Outcome.CONFLICT,null);
  }
  if(remote.tag==null||remote.tag.isEmpty())throw new IOException("Cannot safely update a file without its current tag");
  backend.archive(remote,remoteHash);
  Item updated=backend.update(remote,source);
  if(!remote.id.equals(updated.id))throw new IOException("Provider replaced file identity unexpectedly");
  return result(Outcome.UPDATED,updated,localHash);
 }
 private static Result result(Outcome outcome,Item item,String hash){return new Result(outcome,new Receipt(item.id,item.tag,hash,item.size));}
 static String snapshotHash(String name){
  java.util.regex.Matcher match=java.util.regex.Pattern.compile("[0-9]{1,16}-[0-9a-f-]{36}-([0-9a-f]{64})\\.snapshot").matcher(name);
  return match.matches()?match.group(1):null;
 }
}
