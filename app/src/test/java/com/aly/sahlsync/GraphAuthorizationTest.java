package com.aly.sahlsync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GraphAuthorizationTest {
 @Test public void refreshesAndReusesTokenAfterUnauthorizedRequest()throws Exception{
  List<Boolean> acquisitions=new ArrayList<>();
  Graph.Authorization authorization=new Graph.Authorization(force->{
   acquisitions.add(force);
   return force?"fresh":"cached";
  });
  assertEquals("success",authorization.send(token->{
   if("cached".equals(token))throw new Graph.HttpError(401);
   assertEquals("fresh",token);
   return "success";
  }));
  assertEquals("fresh",authorization.send(token->token));
  assertEquals(Arrays.asList(false,true),acquisitions);
 }

 @Test public void asksForSignInIfRefreshedTokenIsAlsoRejected()throws Exception{
  List<Boolean> acquisitions=new ArrayList<>();
  Graph.Authorization authorization=new Graph.Authorization(force->{acquisitions.add(force);return "token";});
  try{authorization.send(token->{throw new Graph.HttpError(401);});fail();}
  catch(Auth.SignInRequired expected){}
  assertEquals(Arrays.asList(false,true),acquisitions);
 }

 @Test public void preservesOtherHttpErrorsWithoutRefreshing()throws Exception{
  List<Boolean> acquisitions=new ArrayList<>();
  Graph.Authorization authorization=new Graph.Authorization(force->{acquisitions.add(force);return "token";});
  try{authorization.send(token->{throw new Graph.HttpError(412);});fail();}
  catch(Graph.HttpError expected){assertEquals(412,expected.status);}
  assertEquals(Arrays.asList(false),acquisitions);
 }
}
