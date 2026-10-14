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

/** Programa las alarmas que despiertan el celular y lanzan cada grabación. */
public final class Scheduler {

    public static final String[] DAY_LABELS = {"Lu", "Ma", "Mi", "Ju", "Vi", "Sá", "Do"};

    private Scheduler() {}

    /** Próximo momento de ejecución, o -1 si no hay. */
    public static long nextTrigger(Macro m, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, m.hour);
        c.set(Calendar.MINUTE, m.minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (c.getTimeInMillis() > now) {
                if (m.daysMask == 0) return c.getTimeInMillis();
                int idx = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // lunes = 0 ... domingo = 6
                if ((m.daysMask & (1 << idx)) != 0) return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return -1;
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

    /** Programa (o reprograma) la próxima ejecución. Devuelve el momento, o -1. */
    public static long schedule(Context ctx, Macro m) {
        cancel(ctx, m.id);
        if (!m.scheduleEnabled || m.steps.isEmpty()) return -1;
        long t = nextTrigger(m, System.currentTimeMillis());
        if (t < 0) return -1;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return -1;
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

    public static void rescheduleAll(Context ctx) {
        List<Macro> list = MacroStore.load(ctx);
        for (Macro m : list) schedule(ctx, m);
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

    public static String formatDate(long t) {
        return new SimpleDateFormat("EEE dd/MM HH:mm", new Locale("es", "AR")).format(new Date(t));
    }

    public static String describe(Macro m) {
        if (!m.scheduleEnabled) return "Sin programar";
        String s = daysText(m) + " a las " + time(m);
        long t = nextTrigger(m, System.currentTimeMillis());
        if (t > 0) s += "\nPróxima: " + formatDate(t);
        return s;
    }
}
