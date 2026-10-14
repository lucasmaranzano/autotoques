package com.autotoques.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Recibe la alarma programada (y el botón «Detener» de la notificación). */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.autotoques.app.FIRE";
    public static final String ACTION_STOP = "com.autotoques.app.STOP";
    public static final String EXTRA_ID = "id";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context ctx = context.getApplicationContext();
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            MacroService s = MacroService.instance;
            if (s != null) s.stopPlayback();
            Notif.cancelPlaying(ctx);
            return;
        }

        if (!ACTION_FIRE.equals(action)) return;
        final String id = intent.getStringExtra(EXTRA_ID);
        Macro m = MacroStore.get(ctx, id);
        if (m == null) return;

        // Programar la siguiente ejecución antes que nada.
        if (m.daysMask == 0) {
            m.scheduleEnabled = false;
            MacroStore.upsert(ctx, m);
        }
        Scheduler.schedule(ctx, m);

        // Encender la pantalla ya mismo.
        Waker.acquire(ctx, 60_000);

        // Si el sistema reinició el proceso, el servicio de accesibilidad puede
        // tardar unos segundos en volver a conectarse: lo esperamos.
        final PendingResult pending = goAsync();
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                MacroService s = MacroService.instance;
                if (s != null) {
                    s.runScheduled(id);
                    pending.finish();
                } else if (++tries < 30) {
                    h.postDelayed(this, 500);
                } else {
                    Waker.release();
                    Macro mm = MacroStore.get(ctx, id);
                    if (mm != null) {
                        mm.lastRun = System.currentTimeMillis();
                        mm.lastResult = "Falló: servicio de accesibilidad desactivado";
                        MacroStore.upsert(ctx, mm);
                    }
                    Notif.alert(ctx, "AutoToques no pudo ejecutar",
                            "El servicio de accesibilidad está desactivado. Abrí la app y activalo.");
                    pending.finish();
                }
            }
        });
    }
}
