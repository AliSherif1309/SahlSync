package com.aly.sahlsync;

import android.content.Context;
import android.net.Uri;
import com.microsoft.identity.client.*;
import java.io.*;
import java.util.Arrays;

final class Auth {
 static final String[] SCOPES={"https://graph.microsoft.com/Files.ReadWrite"};
 static boolean configured(){return !BuildConfig.MSAL_CLIENT_ID.startsWith("00000000-") && !BuildConfig.MSAL_SIGNATURE.equals("UNCONFIGURED");}
 static final class SignInRequired extends IOException {
  SignInRequired(){super("Sign in to Microsoft to continue");}
 }
 // Call on a worker thread. MSAL alone owns the encrypted credential cache.
 static IMultipleAccountPublicClientApplication open(Context c) throws Exception {
  if(!configured())throw new IOException("Microsoft app registration is not configured in this build");
  File config=new File(c.getNoBackupFilesDir(),"msal.json");
  org.json.JSONObject j=new org.json.JSONObject();
  j.put("client_id",BuildConfig.MSAL_CLIENT_ID);
  j.put("redirect_uri","msauth://com.aly.sahlsync/"+Uri.encode(BuildConfig.MSAL_SIGNATURE));
  j.put("account_mode","MULTIPLE"); j.put("broker_redirect_uri_registered",false); j.put("authorization_user_agent","BROWSER");
  j.put("authorities",new org.json.JSONArray("[{\"type\":\"AAD\",\"audience\":{\"type\":\"AzureADandPersonalMicrosoftAccount\",\"tenant_id\":\"common\"}}]"));
  synchronized(Auth.class){
   try(FileOutputStream out=new FileOutputStream(config)){out.write(j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
   return PublicClientApplication.createMultipleAccountPublicClientApplication(c,config);
  }
 }
 static IAuthenticationResult token(IMultipleAccountPublicClientApplication app,String accountId) throws Exception {
  IAccount account=app.getAccount(accountId);
  if(account==null||!accountId.equals(account.getId()))throw new SignInRequired();
  IAuthenticationResult result=app.acquireTokenSilent(new AcquireTokenSilentParameters.Builder().withScopes(Arrays.asList(SCOPES))
   .forAccount(account).fromAuthority(account.getAuthority()).build());
  if(result.getAccount()==null||!accountId.equals(result.getAccount().getId()))throw new SignInRequired();
  return result;
 }
}
