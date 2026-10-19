package com.autotoques.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Programa las alarmas que despiertan el celular y lanzan cada grabación. */
public final class Scheduler {

    public static final String[] DAY_LABELS = {"Lu", "Ma", "Mi", "Ju", "Vi", "Sá", "Do"};
    public static final int MAX_MARGIN_MIN = 180;

    /** Con horario aleatorio, nunca se programa a menos de este tiempo desde "ahora". */
    static final long MIN_LEAD_MS = 5_000;

    private static final Random RANDOM = new Random();

    private Scheduler() {}

    /** Margen aleatorio efectivo en ms (0 si la opción está desactivada). */
    static long marginMs(Macro m) {
        if (!m.randomEnabled) return 0;
        int min = Math.max(1, Math.min(MAX_MARGIN_MIN, m.randomMarginMin));
        return min * 60_000L;
    }

    /**
     * Calcula la próxima ejecución. Devuelve {base, momento} o null si no hay.
     * - base: el horario configurado (p. ej. 13:00 del día que toca).
     * - momento: base si es exacto, o un instante al azar en [base − margen, base + margen].
     * Cada turno (base) se ejecuta una sola vez, aunque el momento elegido haya
     * caído antes de la hora base.
     */
    static long[] plan(Macro m, long now, Random rnd) {
        long margin = marginMs(m);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, m.hour);
        c.set(Calendar.MINUTE, m.minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        // Se arranca desde ayer: con margen, el turno de ayer a las 23:58 puede
        // ejecutarse hoy a las 00:03.
        c.add(Calendar.DAY_OF_YEAR, -1);
        for (int i = 0; i < 10; i++) {
            long base = c.getTimeInMillis();
            if (base > m.lastFiredBase && dayAllowed(m, c)) {
                if (margin == 0) {
                    if (base > now) return new long[]{base, base};
                } else {
                    long lo = Math.max(base - margin, now + MIN_LEAD_MS);
                    long hi = base + margin;
                    if (hi >= lo) {
                        long at = lo + (long) (rnd.nextDouble() * (hi - lo + 1));
                        return new long[]{base, Math.min(at, hi)};
                    }
                }
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return null;
    }

    private static boolean dayAllowed(Macro m, Calendar c) {
        if (m.daysMask == 0) return true;
        int idx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // lunes = 0 ... domingo = 6
        return (m.daysMask & (1 << idx)) != 0;
    }

    private static PendingIntent fireIntent(Context ctx, String id) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(AlarmReceiver.ACTION_FIRE);
        i.setData(Uri.parse("autotoques://macro/" + id));
        i.putExtra(AlarmReceiver.EXTRA_ID, id);
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static boolean canExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static void cancel(Context ctx, String id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(fireIntent(ctx, id));
    }

    /**
     * Programa (o reprograma) la próxima ejecución y la guarda en la grabación.
     * Si ya había un momento elegido y sigue siendo futuro, se respeta (así el
     * horario aleatorio no cambia cada vez que se abre la app).
     * Devuelve el momento programado, o -1.
     */
    public static long schedule(Context ctx, Macro m) {
        cancel(ctx, m.id);
        long now = System.currentTimeMillis();
        long prevAt = m.plannedAt, prevBase = m.plannedBase;

        if (!m.scheduleEnabled || m.steps.isEmpty()) {
            m.plannedAt = 0;
            m.plannedBase = 0;
        } else if (!(m.plannedAt > now + 1000 && m.plannedBase > m.lastFiredBase)) {
            long[] p = plan(m, now, RANDOM);
            m.plannedBase = p == null ? 0 : p[0];
            m.plannedAt = p == null ? 0 : p[1];
        }
        if (m.plannedAt != prevAt || m.plannedBase != prevBase) persist(ctx, m);
        if (m.plannedAt <= 0) return -1;

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return -1;
        long t = m.plannedAt;
        PendingIntent op = fireIntent(ctx, m.id);
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent show = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE);
        try {
            if (canExact(ctx)) {
                // setAlarmClock es el tipo de alarma más confiable: despierta al
                // celular aunque esté en reposo profundo (Doze).
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, show), op);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, op);
        }
        return t;
    }

    /** Guarda solo los campos de programación, sin pisar otros cambios. */
    private static void persist(Context ctx, Macro m) {
        Macro stored = MacroStore.get(ctx, m.id);
        if (stored == null) return;
        stored.plannedAt = m.plannedAt;
        stored.plannedBase = m.plannedBase;
        MacroStore.upsert(ctx, stored);
    }

    /**
     * Reprograma todas. Con replan = true descarta los momentos ya elegidos
     * (se usa cuando cambia la hora o la zona horaria del celular).
     */
    public static void rescheduleAll(Context ctx, boolean replan) {
        List<Macro> list = MacroStore.load(ctx);
        for (Macro m : list) {
            if (replan) {
                m.plannedAt = 0;
                m.plannedBase = 0;
            }
            schedule(ctx, m);
        }
    }

    public static String daysText(Macro m) {
        if (m.daysMask == 0) return "Una vez";
        if (m.daysMask == 0x7F) return "Todos los días";
        if (m.daysMask == 0x1F) return "Lunes a viernes";
        if (m.daysMask == 0x60) return "Fines de semana";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((m.daysMask & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(DAY_LABELS[i]);
            }
        }
        return sb.toString();
    }

    public static String time(Macro m) {
        return String.format(Locale.US, "%02d:%02d", m.hour, m.minute);
    }

    /** Texto "12:55 y 13:05" para un horario y un margen dados. */
    public static String windowText(int hour, int minute, int marginMin) {
        int base = hour * 60 + minute;
        int lo = ((base - marginMin) % 1440 + 1440) % 1440;
        int hi = (base + marginMin) % 1440;
        return String.format(Locale.US, "%02d:%02d y %02d:%02d", lo / 60, lo % 60, hi / 60, hi % 60);
    }

    public static String formatDate(long t) {
        return new SimpleDateFormat("EEE dd/MM HH:mm", new Locale("es", "AR")).format(new Date(t));
    }

    public static String describe(Macro m) {
        if (!m.scheduleEnabled) return "Sin programar";
        String s = daysText(m) + " a las " + time(m);
        if (m.randomEnabled) s += " (±" + Math.max(1, m.randomMarginMin) + " min al azar)";
        if (m.plannedAt > System.currentTimeMillis()) s += "\nPróxima: " + formatDate(m.plannedAt);
        return s;
    }
}
