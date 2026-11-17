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
  TextView t=new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color);
  t.setPadding(0,dp(c,6),0,dp(c,6)); return t;
 }
 static Button button(Context c,String title,Runnable click){
  Button b=new Button(c); b.setText(title); b.setAllCaps(false); b.setTextColor(DARK);
  b.setBackgroundTintList(ColorStateList.valueOf(GOLD)); b.setMinHeight(dp(c,48));
  b.setOnClickListener(v->click.run()); return b;
 }
}
