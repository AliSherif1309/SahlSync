package com.aly.sahlsync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Google OAuth 2.0 PKCE sign-in and KeyStore-encrypted token store (no Play Services required). */
final class GoogleAuth {
 private static final String AUTH_URL="https://accounts.google.com/o/oauth2/v2/auth";
 private static final String TOKEN_URL="https://oauth2.googleapis.com/token";
 private static final String USERINFO_URL="https://www.googleapis.com/oauth2/v2/userinfo";
 private static final String SCOPE="https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/userinfo.email";
 private static final String KEY_ALIAS="sahl_sync_google_tokens";
 private static final String STORE_FILE="google_accounts.enc";

 interface SignInCallback {
  void onSuccess(Account account);
  void onError(String message);
 }

 static final class Account {
  final String id,email,refreshToken;
  String accessToken;
  long expiresAtMillis;

  Account(String id,String email,String refreshToken,String accessToken,long expiresAtMillis){
   this.id=id;
   this.email=email;
   this.refreshToken=refreshToken;
   this.accessToken=accessToken;
   this.expiresAtMillis=expiresAtMillis;
  }
 }

 private static volatile SignInCallback activeCallback;

 static boolean configured(){return !BuildConfig.GOOGLE_CLIENT_ID.trim().isEmpty();}

 static String redirectUri(){return BuildConfig.GOOGLE_REDIRECT_SCHEME+":/oauth2redirect";}

 static void beginSignIn(Activity activity,SignInCallback callback)throws Exception{
  if(!configured())throw new IOException("Google Drive sign-in is not configured in this build.");
  byte[] verifierBytes=new byte[32];
  byte[] stateBytes=new byte[16];
  SecureRandom rng=new SecureRandom();
  rng.nextBytes(verifierBytes);
  rng.nextBytes(stateBytes);
  String verifier=b64Url(verifierBytes);
  String state=b64Url(stateBytes);
  String challenge=b64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  pendingPrefs(activity).edit()
   .putString("state",state)
   .putString("verifier",verifier)
   .commit();
  activeCallback=callback;
  Uri uri=Uri.parse(AUTH_URL).buildUpon()
   .appendQueryParameter("client_id",BuildConfig.GOOGLE_CLIENT_ID)
   .appendQueryParameter("redirect_uri",redirectUri())
   .appendQueryParameter("response_type","code")
   .appendQueryParameter("scope",SCOPE)
   .appendQueryParameter("code_challenge",challenge)
   .appendQueryParameter("code_challenge_method","S256")
   .appendQueryParameter("access_type","offline")
   .appendQueryParameter("prompt","consent select_account")
   .appendQueryParameter("state",state)
   .build();
  activity.startActivity(new Intent(Intent.ACTION_VIEW,uri));
 }

 static void handleRedirect(Context context,Uri data){
  SignInCallback cb=activeCallback;
  activeCallback=null;
  if(data==null){
   if(cb!=null)cb.onError("Google sign-in was cancelled.");
   return;
  }
  String error=data.getQueryParameter("error");
  if(error!=null&&!error.isEmpty()){
   pendingPrefs(context).edit().clear().apply();
   if(cb!=null)cb.onError("Google sign-in was cancelled or denied.");
   return;
  }
  String code=data.getQueryParameter("code");
  String state=data.getQueryParameter("state");
  SharedPreferences pending=pendingPrefs(context);
  String expectedState=pending.getString("state","");
  String verifier=pending.getString("verifier","");
  pending.edit().clear().apply();
  if(code==null||code.isEmpty()||expectedState.isEmpty()||!expectedState.equals(state)||verifier.isEmpty()){
   if(cb!=null)cb.onError("Google sign-in state mismatch. Try again.");
   return;
  }
  new Thread(()->{
   try{
    Account account=exchangeCode(context.getApplicationContext(),code,verifier);
    if(cb!=null)cb.onSuccess(account);
   }catch(Exception e){
    if(cb!=null)cb.onError("Could not complete Google sign-in. Check your connection and try again.");
   }
  }).start();
 }

 private static Account exchangeCode(Context context,String code,String verifier)throws Exception{
  String body="client_id="+enc(BuildConfig.GOOGLE_CLIENT_ID)
   +"&code="+enc(code)
   +"&code_verifier="+enc(verifier)
   +"&grant_type=authorization_code"
   +"&redirect_uri="+enc(redirectUri());
  JSONObject tokenJson=postForm(TOKEN_URL,body);
  String access=tokenJson.getString("access_token");
  String refresh=tokenJson.optString("refresh_token","");
  long expiresAt=System.currentTimeMillis()+Math.max(60,tokenJson.optLong("expires_in",3600))*1000L-60_000L;
  JSONObject profile=getJson(USERINFO_URL,access);
  String id=profile.getString("id");
  String email=profile.optString("email","Google account");
  synchronized(GoogleAuth.class){
   List<Account> all=loadAccounts(context);
   Account existing=null;
   for(Account a:all)if(id.equals(a.id)){existing=a;break;}
   if(refresh.isEmpty()&&existing!=null)refresh=existing.refreshToken;
   if(refresh.isEmpty())throw new IOException("Google did not return a refresh token");
   Account saved=new Account(id,email,refresh,access,expiresAt);
   all.removeIf(a->id.equals(a.id));
   all.add(saved);
   saveAccounts(context,all);
   return saved;
  }
 }

 static synchronized List<Account> accounts(Context context){
  try{return loadAccounts(context);}
  catch(Exception ignored){return Collections.emptyList();}
 }

 static synchronized Account account(Context context,String id){
  for(Account a:accounts(context))if(id.equals(a.id))return a;
  return null;
 }

 static synchronized void removeAccount(Context context,String id)throws Exception{
  List<Account> all=loadAccounts(context);
  if(!all.removeIf(a->id.equals(a.id)))throw new IOException("Account not found");
  saveAccounts(context,all);
 }

 static synchronized String token(Context context,String accountId,boolean forceRefresh)throws Exception{
  List<Account> all=loadAccounts(context);
  Account found=null;
  for(Account a:all)if(accountId.equals(a.id)){found=a;break;}
  if(found==null)throw new CloudDrive.AuthRequired("Sign in to Google Drive to continue");
  if(!forceRefresh&&found.accessToken!=null&&!found.accessToken.isEmpty()&&System.currentTimeMillis()<found.expiresAtMillis){
   return found.accessToken;
  }
  String body="client_id="+enc(BuildConfig.GOOGLE_CLIENT_ID)
   +"&grant_type=refresh_token"
   +"&refresh_token="+enc(found.refreshToken);
  try{
   JSONObject refreshed=postForm(TOKEN_URL,body);
   found.accessToken=refreshed.getString("access_token");
   found.expiresAtMillis=System.currentTimeMillis()+Math.max(60,refreshed.optLong("expires_in",3600))*1000L-60_000L;
   saveAccounts(context,all);
   return found.accessToken;
  }catch(TokenHttpError e){
   if(e.status==400||e.status==401)throw new CloudDrive.AuthRequired("Sign in to Google Drive to continue",e);
   throw e;
  }
 }

 private static SharedPreferences pendingPrefs(Context c){return c.getSharedPreferences("google-auth-pending",Context.MODE_PRIVATE);}

 private static String b64Url(byte[] data){
  return android.util.Base64.encodeToString(data,android.util.Base64.URL_SAFE|android.util.Base64.NO_PADDING|android.util.Base64.NO_WRAP);
 }

 private static String enc(String value)throws UnsupportedEncodingException{
  return URLEncoder.encode(value,"UTF-8");
 }

 private static final class TokenHttpError extends IOException {
  final int status;
  TokenHttpError(int status){super("Google OAuth HTTP "+status);this.status=status;}
 }

 private static JSONObject postForm(String url,String body)throws Exception{
  HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(30000);
   c.setRequestMethod("POST");c.setDoOutput(true);
   c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
   byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
   c.setFixedLengthStreamingMode(bytes.length);
   try(OutputStream out=c.getOutputStream()){out.write(bytes);}
   int status=c.getResponseCode();
   if(status<200||status>=300)throw new TokenHttpError(status);
   return readJson(c);
  }finally{c.disconnect();}
 }

 private static JSONObject getJson(String url,String token)throws Exception{
  HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();
  try{
   c.setInstanceFollowRedirects(false);c.setConnectTimeout(30000);c.setReadTimeout(30000);
   c.setRequestMethod("GET");c.setRequestProperty("Authorization","Bearer "+token);
   int status=c.getResponseCode();
   if(status<200||status>=300)throw new TokenHttpError(status);
   return readJson(c);
  }finally{c.disconnect();}
 }

 private static JSONObject readJson(HttpURLConnection c)throws Exception{
  try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buf=new byte[4096];int n;
   while((n=in.read(buf))!=-1){
    if(out.size()+n>1024*1024)throw new IOException("Response too large");
    out.write(buf,0,n);
   }
   return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
  }
 }

 private static SecretKey secretKey()throws Exception{
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
  ks.load(null);
  if(ks.containsAlias(KEY_ALIAS))return (SecretKey)ks.getKey(KEY_ALIAS,null);
  KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
  gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
   .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
   .setKeySize(256)
   .build());
  return gen.generateKey();
 }

 private static List<Account> loadAccounts(Context context)throws Exception{
  File file=new File(context.getNoBackupFilesDir(),STORE_FILE);
  if(!file.exists())return new ArrayList<>();
  byte[] raw=java.nio.file.Files.readAllBytes(file.toPath());
  if(raw.length<13)return new ArrayList<>();
  byte[] iv=Arrays.copyOfRange(raw,0,12);
  byte[] ciphertext=Arrays.copyOfRange(raw,12,raw.length);
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
  cipher.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,iv));
  String json=new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
  JSONArray arr=new JSONArray(json);
  List<Account> list=new ArrayList<>(arr.length());
  for(int i=0;i<arr.length();i++){
   JSONObject o=arr.getJSONObject(i);
   list.add(new Account(o.getString("id"),o.getString("email"),o.getString("refreshToken"),
    o.optString("accessToken"),o.optLong("expiresAtMillis",0)));
  }
  return list;
 }

 private static void saveAccounts(Context context,List<Account> list)throws Exception{
  JSONArray arr=new JSONArray();
  for(Account a:list){
   arr.put(new JSONObject().put("id",a.id).put("email",a.email)
    .put("refreshToken",a.refreshToken).put("accessToken",a.accessToken==null?"":a.accessToken)
    .put("expiresAtMillis",a.expiresAtMillis));
  }
  byte[] plain=arr.toString().getBytes(StandardCharsets.UTF_8);
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
  cipher.init(Cipher.ENCRYPT_MODE,secretKey());
  byte[] iv=cipher.getIV();
  byte[] enc=cipher.doFinal(plain);
  File file=new File(context.getNoBackupFilesDir(),STORE_FILE);
  try(FileOutputStream out=new FileOutputStream(file)){
   out.write(iv);
   out.write(enc);
  }
 }
}
