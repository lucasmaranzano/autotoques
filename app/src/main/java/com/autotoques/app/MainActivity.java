package com.autotoques.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    public static final String EXTRA_NEW = "new_macro";

    private static final int C_TEXT = 0xFF1F1F1F;
    private static final int C_MUTED = 0xFF6B6B6B;
    private static final int C_OK = 0xFF1E8E3E;
    private static final int C_WARN = 0xFFC5221F;
    private static final int C_ACCENT = 0xFF1A73E8;

    private LinearLayout root;
    private String renameAfterRecord;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFF6F7F9);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Scheduler.rescheduleAll(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String id = i.getStringExtra(EXTRA_NEW);
        if (id != null) {
            renameAfterRecord = id;
            i.removeExtra(EXTRA_NEW);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
        if (renameAfterRecord != null) {
            Macro m = MacroStore.get(this, renameAfterRecord);
            renameAfterRecord = null;
            if (m != null) showRename(m, true);
        }
    }

    // =====================================================================

    private void build() {
        root.removeAllViews();

        // --- Permisos ---
        LinearLayout perms = card();
        perms.addView(title("Configuración"));

        boolean acc = MacroService.instance != null;
        perms.addView(statusRow("Servicio de accesibilidad", acc,
                acc ? "Activado" : "Desactivado: necesario para grabar y reproducir",
                acc ? null : "Activar", () -> {
                    toast("Buscá «AutoToques» en la lista y activalo");
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }));

        boolean exact = Scheduler.canExact(this);
        perms.addView(statusRow("Alarmas exactas", exact,
                exact ? "Permitidas" : "Sin permiso: puede ejecutarse con minutos de atraso",
                exact ? null : "Permitir", () -> {
                    if (Build.VERSION.SDK_INT >= 31) {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + getPackageName())));
                    }
                }));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        perms.addView(statusRow("Sin restricción de batería", batt,
                batt ? "OK" : "El sistema puede frenar la app mientras duerme",
                batt ? null : "Quitar", () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                }));

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean secure = km != null && km.isDeviceSecure();
        perms.addView(statusRow("Pantalla de bloqueo", !secure,
                secure ? "Tenés PIN/patrón/huella: la app no puede desbloquear el celular sola"
                        : "Sin código: la app puede desbloquear",
                secure ? "Ajustes" : null, () -> startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS))));
        root.addView(perms);

        // --- Botón grabar ---
        Button rec = new Button(this);
        rec.setText("●  Nueva grabación");
        rec.setTextColor(Color.WHITE);
        rec.setTextSize(17);
        rec.setAllCaps(false);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0xFFD93025);
        rbg.setCornerRadius(dp(12));
        rec.setBackground(rbg);
        rec.setPadding(dp(12), dp(14), dp(12), dp(14));
        rec.setOnClickListener(v -> startRecording());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(4), 0, dp(4));
        root.addView(rec, rlp);
        TextView hint = text("La grabación arranca desde la pantalla de inicio. Usá la barrita flotante "
                + "para Atrás ◁, Inicio ○ y Recientes ▢, y tocá «Guardar» al terminar.", 13, C_MUTED);
        hint.setPadding(dp(4), dp(4), dp(4), dp(12));
        root.addView(hint);

        // --- Grabaciones ---
        List<Macro> list = MacroStore.load(this);
        if (list.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("Todavía no hay grabaciones.", 15, C_MUTED));
            root.addView(empty);
        }
        for (Macro m : list) root.addView(macroCard(m));
    }

    private View macroCard(Macro m) {
        LinearLayout c = card();
        c.addView(title(m.name));

        long secs = Math.round(m.estimateMs() / 1000.0);
        c.addView(text(m.steps.size() + " pasos · ~" + secs + " s", 13, C_MUTED));

        TextView sch = text(Scheduler.describe(m), 15, m.scheduleEnabled ? C_ACCENT : C_MUTED);
        sch.setPadding(0, dp(6), 0, 0);
        c.addView(sch);

        if (m.lastRun > 0) {
            boolean ok = "OK".equals(m.lastResult);
            c.addView(text("Última: " + Scheduler.formatDate(m.lastRun) + " · " + m.lastResult,
                    13, ok ? C_OK : C_WARN));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        row.addView(smallButton("▶ Probar", v -> {
            MacroService s = MacroService.instance;
            if (s == null) {
                toast("Primero activá el servicio de accesibilidad");
                return;
            }
            s.playNow(m.id);
        }), weight());
        row.addView(smallButton("⏰ Programar", v -> showSchedule(m)), weight());
        row.addView(smallButton("✎", v -> showRename(m, false)));
        row.addView(smallButton("🗑", v -> confirmDelete(m)));
        c.addView(row);
        return c;
    }

    // =====================================================================

    private void startRecording() {
        MacroService s = MacroService.instance;
        if (s == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Falta activar el servicio")
                    .setMessage("Para grabar y reproducir toques, activá «AutoToques» en Ajustes → "
                            + "Accesibilidad.\n\nSi la opción aparece gris: Ajustes → Apps → AutoToques → "
                            + "menú ⋮ → «Permitir ajustes restringidos», y volvé a intentar.")
                    .setPositiveButton("Ir a Accesibilidad",
                            (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        s.startRecording();
    }

    private void showRename(Macro m, boolean isNew) {
        EditText et = new EditText(this);
        et.setText(m.name);
        et.setSelectAllOnFocus(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isNew ? "Grabación guardada (" + m.steps.size() + " pasos)" : "Renombrar")
                .setView(box)
                .setPositiveButton(isNew ? "Guardar y programar" : "Guardar", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) m.name = n;
                    MacroStore.upsert(this, m);
                    build();
                    if (isNew) showSchedule(m);
                })
                .setNegativeButton(isNew ? "Solo guardar" : "Cancelar", (d, w) -> {
                    if (isNew) {
                        String n = et.getText().toString().trim();
                        if (!n.isEmpty()) m.name = n;
                        MacroStore.upsert(this, m);
                        build();
                    }
                });
        b.show();
    }

    private void confirmDelete(Macro m) {
        new AlertDialog.Builder(this)
                .setTitle("¿Borrar «" + m.name + "»?")
                .setPositiveButton("Borrar", (d, w) -> {
                    Scheduler.cancel(this, m.id);
                    MacroStore.delete(this, m.id);
                    build();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showSchedule(Macro m) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), dp(8));

        Switch enabled = new Switch(this);
        enabled.setText("Programación activa");
        enabled.setTextSize(16);
        enabled.setChecked(true);
        v.addView(enabled);

        // Hora
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER);
        NumberPicker hp = new NumberPicker(this);
        hp.setMinValue(0);
        hp.setMaxValue(23);
        hp.setValue(m.hour);
        hp.setFormatter(i -> String.format(Locale.US, "%02d", i));
        NumberPicker mp = new NumberPicker(this);
        mp.setMinValue(0);
        mp.setMaxValue(59);
        mp.setValue(m.minute);
        mp.setFormatter(i -> String.format(Locale.US, "%02d", i));
        TextView colon = text(" : ", 24, C_TEXT);
        timeRow.addView(hp);
        timeRow.addView(colon);
        timeRow.addView(mp);
        v.addView(timeRow);

        // Días
        v.addView(label("Días"));
        final CheckBox[] days = new CheckBox[7];
        LinearLayout r1 = new LinearLayout(this);
        LinearLayout r2 = new LinearLayout(this);
        for (int i = 0; i < 7; i++) {
            days[i] = new CheckBox(this);
            days[i].setText(Scheduler.DAY_LABELS[i]);
            days[i].setChecked((m.daysMask & (1 << i)) != 0);
            (i < 4 ? r1 : r2).addView(days[i], weight());
        }
        v.addView(r1);
        v.addView(r2);
        LinearLayout quick = new LinearLayout(this);
        quick.addView(smallButton("Todos", x -> { for (CheckBox d : days) d.setChecked(true); }), weight());
        quick.addView(smallButton("Lun–Vie", x -> {
            for (int i = 0; i < 7; i++) days[i].setChecked(i < 5);
        }), weight());
        quick.addView(smallButton("Ninguno", x -> { for (CheckBox d : days) d.setChecked(false); }), weight());
        v.addView(quick);
        v.addView(text("Sin días marcados = se ejecuta una sola vez.", 12, C_MUTED));

        // Opciones
        v.addView(label("Espera antes de empezar (segundos)"));
        EditText delay = numberField(m.initialDelaySec);
        v.addView(delay);
        v.addView(label("Pausa extra entre pasos (milisegundos)"));
        EditText pause = numberField(m.extraPauseMs);
        v.addView(pause);
        v.addView(text("Útil si a esa hora las apps cargan más lento que cuando grabaste.", 12, C_MUTED));
        CheckBox home = new CheckBox(this);
        home.setText("Ir a la pantalla de inicio antes de empezar");
        home.setChecked(m.goHomeFirst);
        v.addView(home);

        ScrollView sc = new ScrollView(this);
        sc.addView(v);

        new AlertDialog.Builder(this)
                .setTitle("Programar «" + m.name + "»")
                .setView(sc)
                .setPositiveButton("Guardar", (d, w) -> {
                    m.scheduleEnabled = enabled.isChecked();
                    m.hour = hp.getValue();
                    m.minute = mp.getValue();
                    int mask = 0;
                    for (int i = 0; i < 7; i++) if (days[i].isChecked()) mask |= (1 << i);
                    m.daysMask = mask;
                    m.initialDelaySec = parse(delay, 3, 0, 600);
                    m.extraPauseMs = parse(pause, 0, 0, 60_000);
                    m.goHomeFirst = home.isChecked();
                    MacroStore.upsert(this, m);
                    long t = Scheduler.schedule(this, m);
                    if (t > 0) toast("Próxima ejecución: " + Scheduler.formatDate(t));
                    else toast("Programación desactivada");
                    build();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // =====================================================================
    // Helpers de interfaz

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFFE2E4E8);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView title(String s) {
        TextView t = text(s, 17, C_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, C_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private View statusRow(String name, boolean ok, String detail, String action, Runnable onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text((ok ? "✓ " : "⚠ ") + name, 15, ok ? C_OK : C_WARN));
        col.addView(text(detail, 12, C_MUTED));
        row.addView(col, weight());
        if (action != null) row.addView(smallButton(action, v -> onAction.run()));
        return row;
    }

    private Button smallButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(l);
        return b;
    }

    private EditText numberField(int value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        return e;
    }

    private static int parse(EditText e, int def, int min, int max) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception ex) {
            return def;
        }
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
