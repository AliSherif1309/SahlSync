package com.aly.sahlsync;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.widget.*;

/** Tokens shared with SahlTV common/Ui.kt. Keep this palette aligned across Sahl apps. */
final class SahlTheme {
 static final int TOP=Color.parseColor("#0E1A24"), BOTTOM=Color.parseColor("#10261F"),
 PANEL=Color.parseColor("#13241F"), TEXT=Color.parseColor("#EEF3F1"),
 MUTED=Color.parseColor("#8FA39C"), GOLD=Color.parseColor("#E3B85C"), DARK=Color.parseColor("#0B1E1A");
 static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 static GradientDrawable rounded(Context c,int color){
  GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,18)); return d;
 }
 static TextView label(Context c,String value,int size,int color){
  TextView t=new TextView(c); t.setText(Tr.t(c,value)); t.setTextSize(size); t.setTextColor(color);
  t.setPadding(0,dp(c,6),0,dp(c,6)); Fonts.apply(c,t); return t;
 }
 /** Primary action: gold pill. */
 static Button button(Context c,String title,Runnable click){
  Button b=base(c,title,click);
  b.setTextColor(DARK);
  b.setBackground(ripple(c,GOLD,0,Color.parseColor("#33000000")));
  return b;
 }
 /** Secondary action: outlined pill, so the main action on each screen stands out. */
 static Button secondary(Context c,String title,Runnable click){
  Button b=base(c,title,click);
  b.setTextColor(TEXT);
  b.setBackground(ripple(c,PANEL,Color.parseColor("#2E4A42"),Color.parseColor("#33E3B85C")));
  return b;
 }
 private static Button base(Context c,String title,Runnable click){
  Button b=new Button(c); b.setText(Tr.t(c,title)); b.setAllCaps(false); b.setTextSize(15);
  b.setStateListAnimator(null); b.setMinHeight(dp(c,52)); b.setPadding(dp(c,18),dp(c,10),dp(c,18),dp(c,10));
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
  p.setMargins(0,dp(c,5),0,dp(c,5)); b.setLayoutParams(p);
  b.setOnClickListener(v->click.run()); Fonts.apply(c,b); return b;
 }
 private static android.graphics.drawable.Drawable ripple(Context c,int fill,int stroke,int pressed){
  GradientDrawable shape=new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(c,26));
  if(stroke!=0)shape.setStroke(dp(c,1),stroke);
  return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(pressed),shape,null);
 }
 /** Rounded card for grouping one folder pair or one section. */
 static LinearLayout card(Context c){
  LinearLayout card=new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
  card.setBackground(rounded(c,PANEL)); int pad=dp(c,16); card.setPadding(pad,pad,pad,pad);
  card.setElevation(dp(c,2));
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT);
  p.setMargins(0,dp(c,8),0,dp(c,8)); card.setLayoutParams(p); return card;
 }
}
