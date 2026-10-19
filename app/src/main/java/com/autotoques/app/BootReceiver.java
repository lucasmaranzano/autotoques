package com.autotoques.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vuelve a programar las alarmas tras reiniciar, actualizar la app o cambiar la hora. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        boolean clockChanged = Intent.ACTION_TIME_CHANGED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a);
        Scheduler.rescheduleAll(context.getApplicationContext(), clockChanged);
    }
}
