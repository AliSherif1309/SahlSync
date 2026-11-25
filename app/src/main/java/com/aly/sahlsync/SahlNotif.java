package com.aly.sahlsync;

import android.app.Notification;
import android.content.Context;

final class SahlNotif {
    static final int EMERALD = 0xFF0B5D4B;

    private SahlNotif() {}

    static Notification.Builder apply(Context ctx, Notification.Builder b) {
        return b.setSmallIcon(R.drawable.ic_sahl_notif)
            .setColor(EMERALD);
    }
}
