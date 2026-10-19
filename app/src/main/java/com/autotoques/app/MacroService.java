package com.autotoques.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Corazón de la app. Como servicio de accesibilidad puede:
 *  - poner una capa transparente sobre la pantalla para registrar los toques,
 *  - inyectar toques/deslizamientos (dispatchGesture),
 *  - presionar Atrás / Inicio / Recientes.
 */
public class MacroService extends AccessibilityService {

    public static volatile MacroService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;

    // ---- Grabación ----
    private boolean recording;
    private View touchLayer;
    private LinearLayout bar;
    private TextView counter;
    private WindowManager.LayoutParams touchLp;
    private WindowManager.LayoutParams barLp;
    private List<Macro.Step> recSteps;
    private long lastEventEnd;
    private long downTime;
    private int pointerId;
    private float offX, offY;
    private ArrayList<Float> cur;
    private boolean injecting;

    // ---- Reproducción ----
    private boolean playing;
    private Macro playMacro;
    private int playIndex;
    private int runToken;
    private String pendingId;
    private int wakeToken;
    /** Ejecuciones programadas que llegaron mientras otra estaba en curso. */
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        instance = null;
        super.onDestroy();
    }

    private void cleanup() {
        stopPlayback();
        recording = false;
        removeRecordingUi();
    }

    public boolean isBusy() {
        return recording || playing || pendingId != null;
    }

    // =====================================================================
    // GRABACIÓN
    // =====================================================================

    public void startRecording() {
        if (isBusy()) {
            toast("Ya hay una grabación o reproducción en curso");
            return;
        }
        recording = true;
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(this::showRecordingUi, 700);
    }

    private void showRecordingUi() {
        if (!recording) return;
        recSteps = new ArrayList<>();
        injecting = false;
        cur = null;

        touchLayer = new View(this);
        touchLayer.setBackgroundColor(0x14FF3B30); // tinte rojo muy suave = "grabando"
        touchLayer.setOnTouchListener((v, e) -> {
            onLayerTouch(e);
            return true;
        });
        touchLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            touchLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        bar = buildBar();
        try {
            wm.addView(touchLayer, touchLp);
            wm.addView(bar, barLp);
        } catch (Exception ex) {
            toast("No se pudo iniciar la grabación: " + ex.getMessage());
            recording = false;
            removeRecordingUi();
            return;
        }
        lastEventEnd = SystemClock.uptimeMillis();
        updateCounter();
        toast("Grabando: usá el celular normalmente");
    }

    private void onLayerTouch(MotionEvent e) {
        if (injecting) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downTime = e.getEventTime();
                pointerId = e.getPointerId(0);
                offX = e.getRawX() - e.getX();
                offY = e.getRawY() - e.getY();
                cur = new ArrayList<>();
                addPt(e.getRawX(), e.getRawY(), 0, true);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (cur == null) return;
                int pi = e.findPointerIndex(pointerId);
                if (pi < 0) return;
                for (int h = 0; h < e.getHistorySize(); h++) {
                    addPt(e.getHistoricalX(pi, h) + offX, e.getHistoricalY(pi, h) + offY,
                            e.getHistoricalEventTime(h) - downTime, false);
                }
                addPt(e.getX(pi) + offX, e.getY(pi) + offY, e.getEventTime() - downTime, false);
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (cur == null) return;
                int pi = e.findPointerIndex(pointerId);
                if (pi < 0) pi = 0;
                addPt(e.getX(pi) + offX, e.getY(pi) + offY, e.getEventTime() - downTime, true);
                float[] pts = new float[cur.size()];
                for (int i = 0; i < pts.length; i++) pts[i] = cur.get(i);
                cur = null;
                Macro.Step s = Macro.Step.gesture(Math.max(0, downTime - lastEventEnd), pts);
                recSteps.add(s);
                lastEventEnd = e.getEventTime();
                updateCounter();
                injectDuringRecording(s);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                cur = null;
                break;
            default:
                break;
        }
    }

    /** Agrega un punto al trazo, descartando los que están a menos de 4 px del anterior. */
    private void addPt(float x, float y, long t, boolean force) {
        int n = cur.size();
        if (!force && n >= 3) {
            float dx = x - cur.get(n - 3);
            float dy = y - cur.get(n - 2);
            if (dx * dx + dy * dy < 16f) return;
        }
        if (n >= 3 && t < cur.get(n - 1)) t = (long) (float) cur.get(n - 1);
        cur.add(x);
        cur.add(y);
        cur.add((float) t);
    }

    /** La capa "se come" el toque: lo reenviamos al celular para que la app de abajo lo reciba. */
    private void injectDuringRecording(Macro.Step s) {
        injecting = true;
        setLayerTouchable(false);
        handler.postDelayed(() -> {
            boolean ok;
            try {
                ok = dispatchGesture(buildGesture(s), new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) { restoreLayer(); }
                    @Override public void onCancelled(GestureDescription g) { restoreLayer(); }
                }, handler);
            } catch (Exception ex) {
                ok = false;
            }
            if (!ok) restoreLayer();
        }, 60);
    }

    private void restoreLayer() {
        handler.postDelayed(() -> {
            if (recording) setLayerTouchable(true);
            injecting = false;
        }, 40);
    }

    private void setLayerTouchable(boolean touchable) {
        if (touchLayer == null || touchLp == null) return;
        if (touchable) touchLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else touchLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(touchLayer, touchLp);
        } catch (Exception ignored) {
        }
    }

    private void recordGlobal(int type) {
        if (!recording || recSteps == null) return;
        long now = SystemClock.uptimeMillis();
        Macro.Step s = Macro.Step.action(type, Math.max(0, now - lastEventEnd));
        recSteps.add(s);
        lastEventEnd = now;
        performGlobalAction(s.globalAction());
        updateCounter();
    }

    private void updateCounter() {
        if (counter != null) counter.setText("● " + (recSteps == null ? 0 : recSteps.size()));
    }

    private LinearLayout buildBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE202124);
        bg.setCornerRadius(dp(24));
        b.setBackground(bg);
        b.setPadding(dp(4), 0, dp(4), 0);

        TextView handle = barButton("⠿", null);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY();
                        ox = barLp.x; oy = barLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        barLp.x = ox + (int) (e.getRawX() - sx);
                        barLp.y = oy + (int) (e.getRawY() - sy);
                        try { wm.updateViewLayout(bar, barLp); } catch (Exception ignored) { }
                        return true;
                    default:
                        return true;
                }
            }
        });
        counter = barButton("● 0", null);
        counter.setTextColor(0xFFFF5252);

        b.addView(handle);
        b.addView(counter);
        b.addView(barButton("◁", v -> recordGlobal(Macro.Step.BACK)));
        b.addView(barButton("○", v -> recordGlobal(Macro.Step.HOME)));
        b.addView(barButton("▢", v -> recordGlobal(Macro.Step.RECENTS)));
        b.addView(barButton("Guardar", v -> stopRecording(true)));
        b.addView(barButton("✕", v -> stopRecording(false)));

        barLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        barLp.gravity = Gravity.TOP | Gravity.START;
        barLp.x = dp(8);
        barLp.y = dp(140);
        return b;
    }

    private TextView barButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setPadding(dp(9), dp(10), dp(9), dp(10));
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    public void stopRecording(boolean save) {
        if (!recording) return;
        recording = false;
        removeRecordingUi();
        List<Macro.Step> steps = recSteps;
        recSteps = null;

        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (save && steps != null && !steps.isEmpty()) {
            String when = new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date());
            Macro m = Macro.create("Grabación " + when);
            m.steps.addAll(steps);
            MacroStore.upsert(this, m);
            i.putExtra(MainActivity.EXTRA_NEW, m.id);
        } else if (save) {
            toast("No se grabó ningún toque");
        }
        startActivity(i);
    }

    private void removeRecordingUi() {
        if (wm != null) {
            if (touchLayer != null) try { wm.removeView(touchLayer); } catch (Exception ignored) { }
            if (bar != null) try { wm.removeView(bar); } catch (Exception ignored) { }
        }
        touchLayer = null;
        bar = null;
        counter = null;
    }

    // =====================================================================
    // REPRODUCCIÓN
    // =====================================================================

    /** Llamado por la alarma: enciende, desbloquea y reproduce. */
    public void runScheduled(String id) {
        Macro m = MacroStore.get(this, id);
        if (m == null) {
            Waker.release();
            return;
        }
        if (recording) {
            m.lastRun = System.currentTimeMillis();
            m.lastResult = "Omitida: estabas grabando en ese momento";
            MacroStore.upsert(this, m);
            return;
        }
        if (playing || pendingId != null) {
            // Otra está corriendo: esta se ejecuta apenas termine.
            if (!queue.contains(id) && !id.equals(pendingId)) queue.add(id);
            return;
        }
        pendingId = id;
        Waker.acquire(this, m.estimateMs() + 60_000);
        try {
            startActivity(new Intent(this, WakeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception ignored) {
        }
        final int token = ++wakeToken;
        // Si la pantalla de desbloqueo no responde, arrancamos igual.
        handler.postDelayed(() -> {
            if (token == wakeToken && pendingId != null) onUnlocked();
        }, 6000);
    }

    /** Llamado por WakeActivity cuando la pantalla ya está encendida y desbloqueada. */
    public void onUnlocked() {
        String id = pendingId;
        pendingId = null;
        wakeToken++;
        if (id != null) startPlayback(id);
    }

    /** Reproducir ahora (botón «Probar»). */
    public void playNow(String id) {
        if (isBusy()) {
            toast("Ya hay una grabación o reproducción en curso");
            return;
        }
        startPlayback(id);
    }

    private void startPlayback(String id) {
        Macro m = MacroStore.get(this, id);
        if (m == null || m.steps.isEmpty()) {
            Waker.release();
            return;
        }
        playing = true;
        playMacro = m;
        playIndex = 0;
        final int token = ++runToken;
        Waker.acquire(this, m.estimateMs() + 60_000);
        Notif.playing(this, m.name);

        long delay = m.initialDelaySec * 1000L;
        if (m.goHomeFirst) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            delay += 800;
        }
        handler.postDelayed(() -> playStep(token), delay);
    }

    private void playStep(int token) {
        if (token != runToken || !playing || playMacro == null) return;
        if (playIndex >= playMacro.steps.size()) {
            finishPlayback("OK");
            return;
        }
        final Macro.Step s = playMacro.steps.get(playIndex);
        long wait = playIndex == 0 ? 0 : s.delayBefore + playMacro.extraPauseMs;
        handler.postDelayed(() -> execStep(token, s), Math.max(0, wait));
    }

    private void execStep(int token, Macro.Step s) {
        if (token != runToken || !playing) return;
        if (s.type == Macro.Step.GESTURE) {
            boolean ok;
            try {
                ok = dispatchGesture(buildGesture(s), new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) { next(token); }
                    @Override public void onCancelled(GestureDescription g) { next(token); }
                }, handler);
            } catch (Exception ex) {
                ok = false;
            }
            if (!ok) next(token);
        } else {
            performGlobalAction(s.globalAction());
            handler.postDelayed(() -> next(token), 300);
        }
    }

    private void next(int token) {
        if (token != runToken) return;
        playIndex++;
        playStep(token);
    }

    private void finishPlayback(String result) {
        playing = false;
        Notif.cancelPlaying(this);
        if (playMacro != null) {
            Macro m = MacroStore.get(this, playMacro.id);
            if (m != null) {
                m.lastRun = System.currentTimeMillis();
                m.lastResult = result;
                MacroStore.upsert(this, m);
            }
        }
        playMacro = null;
        if (!queue.isEmpty()) {
            final String nextId = queue.poll();
            handler.postDelayed(() -> runScheduled(nextId), 1500);
        } else {
            Waker.release();
        }
    }

    public void stopPlayback() {
        queue.clear();
        boolean wasPending = pendingId != null;
        runToken++;
        wakeToken++;
        pendingId = null;
        if (playing) finishPlayback("Detenida por el usuario");
        else if (wasPending) Waker.release();
    }

    // =====================================================================

    static GestureDescription buildGesture(Macro.Step s) {
        float[] p = s.pts;
        int n = p.length / 3;
        Path path = new Path();
        path.moveTo(Math.max(0f, p[0]), Math.max(0f, p[1]));
        for (int i = 1; i < n; i++) {
            path.lineTo(Math.max(0f, p[i * 3]), Math.max(0f, p[i * 3 + 1]));
        }
        long dur = (long) p[(n - 1) * 3 + 2];
        if (dur < 1) dur = 1;
        long max = GestureDescription.getMaxGestureDuration();
        if (dur > max) dur = max;
        return new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, dur))
                .build();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
