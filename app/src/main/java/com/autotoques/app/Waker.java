package com.autotoques.app;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

/** Enciende la pantalla y la mantiene encendida mientras dura la reproducción. */
public final class Waker {

    private static PowerManager.WakeLock lock;
    private static long until;

    private Waker() {}

    /** Enciende la pantalla. Nunca acorta un bloqueo que ya dura más. */
    @SuppressWarnings("deprecation")
    public static synchronized void acquire(Context ctx, long timeoutMs) {
        long timeout = Math.max(10_000L, timeoutMs);
        long newUntil = SystemClock.elapsedRealtime() + timeout;
        if (lock != null && lock.isHeld() && until >= newUntil) return;
        release();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "AutoToques:run");
        lock.setReferenceCounted(false);
        lock.acquire(timeout);
        until = newUntil;
    }

    public static synchronized void release() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignored) {
        }
        lock = null;
        until = 0;
    }
}
