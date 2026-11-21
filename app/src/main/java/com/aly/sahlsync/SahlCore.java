package com.aly.sahlsync;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Sahl Core (light): crash report screen + Sahl Union permission (in the manifest). */
public final class SahlCore {
  private SahlCore() {}

  /** Saves any crash and opens a simple report screen. */
  public static void catchCrashes(Context c) {
    final Context app = c.getApplicationContext();
    final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    if (prev instanceof Handler) return;
    Thread.setDefaultUncaughtExceptionHandler(new Handler(app, prev));
  }

  static final class Handler implements Thread.UncaughtExceptionHandler {
    final Context app; final Thread.UncaughtExceptionHandler prev;
    Handler(Context app, Thread.UncaughtExceptionHandler prev) { this.app = app; this.prev = prev; }
    @Override public void uncaughtException(Thread t, Throwable e) {
      try {
        String log = app.getPackageName() + "\n" + android.util.Log.getStackTraceString(e);
        if (log.length() > 6000) log = log.substring(0, 6000);
        app.startActivity(new Intent(app, CrashActivity.class).putExtra("log", log)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
      } catch (Exception x) { if (prev != null) prev.uncaughtException(t, e); }
    }
  }

  /** Plain crash-report screen. */
  public static final class CrashActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
      super.onCreate(b);
      final String log = getIntent().getStringExtra("log") != null ? getIntent().getStringExtra("log") : "No report";
      LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(40, 80, 40, 40);
      TextView title = new TextView(this); title.setText("Sahl crash report"); title.setTextSize(22f); box.addView(title);
      Button copy = new Button(this); copy.setText("Copy report");
      copy.setOnClickListener(v -> {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("crash", log));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
      });
      box.addView(copy);
      ScrollView sv = new ScrollView(this); TextView tv = new TextView(this); tv.setText(log); tv.setTextSize(11f); tv.setTextIsSelectable(true);
      sv.addView(tv); box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
      setContentView(box);
    }
  }
}
