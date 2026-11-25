package com.aly.sahlsync;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

final class SahlNotif {
    static final int EMERALD = 0xFF0B5D4B;
    private static volatile Bitmap cached;

    private SahlNotif() {}

    static Notification.Builder apply(Context ctx, Notification.Builder b) {
        return b.setSmallIcon(R.drawable.ic_sahl_notif)
            .setColor(EMERALD)
            .setLargeIcon(largeIcon(ctx));
    }

    static Bitmap largeIcon(Context ctx) {
        if (cached != null) return cached;
        final int size = 128;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0f, 0f, size, size), 28f, 28f, Path.Direction.CW);
        c.clipPath(clip);
        c.save();
        c.scale(1.32f, 1.32f, size / 2f, size / 2f);
        Drawable bg = ctx.getDrawable(R.drawable.ic_sahl_bg);
        if (bg != null) { bg.setBounds(0, 0, size, size); bg.draw(c); }
        Drawable fg = ctx.getDrawable(R.drawable.ic_sahl_fg);
        if (fg != null) { fg.setBounds(0, 0, size, size); fg.draw(c); }
        c.restore();
        cached = bmp;
        return bmp;
    }
}
