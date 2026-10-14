package com.autotoques.app;

import android.content.Context;
import android.os.PowerManager;

/** Enciende la pantalla y la mantiene encendida mientras dura la reproducción. */
public final class Waker {

    private static PowerManager.WakeLock lock;

    private Waker() {}

    @SuppressWarnings("deprecation")
    public static synchronized void acquire(Context ctx, long timeoutMs) {
        release();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "AutoToques:run");
        lock.setReferenceCounted(false);
        lock.acquire(Math.max(10_000L, timeoutMs));
    }

    public static synchronized void release() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
        lock = null;
    }
}
