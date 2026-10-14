package com.autotoques.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

public final class Notif {

    private static final String CH_RUN = "run";
    private static final String CH_ALERT = "alert";
    private static final int ID_RUN = 1;
    private static final int ID_ALERT = 2;

    private Notif() {}

    private static NotificationManager nm(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Reproducción en curso",
                    NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Avisos",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        return nm;
    }

    public static void playing(Context c, String name) {
        NotificationManager nm = nm(c);
        if (nm == null) return;
        Intent stop = new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_STOP);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Reproduciendo: " + name)
                .setContentText("Tocá «Detener» para cancelar")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Detener", pi).build())
                .build();
        try {
            nm.notify(ID_RUN, n);
        } catch (SecurityException ignored) {
        }
    }

    public static void cancelPlaying(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_RUN);
    }

    public static void alert(Context c, String title, String text) {
        NotificationManager nm = nm(c);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(ID_ALERT, n);
        } catch (SecurityException ignored) {
        }
    }
}
