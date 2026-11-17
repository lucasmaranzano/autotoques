package com.autotoques.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Corazón de la app. Como servicio de accesibilidad puede:
 *  - poner una capa transparente sobre la pantalla para registrar los toques,
 *  - inyectar toques/deslizamientos (dispatchGesture),
 *  - presionar Atrás / Inicio / Recientes,
 *  - capturar la pantalla para buscar una imagen y tocar donde esté.
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
    private boolean cropping;
    private CropOverlay cropView;

    /** Hilo aparte para capturas y búsqueda de imagen (no traba la pantalla). */
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    // Recorte del paso de imagen en curso, ya decodificado.
    private Macro.Step tplStep;
    private int[] tplPx;
    private int tplW, tplH;

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
        toast("Grabando. 🎯 = tocar buscando una imagen · ✔ = guardar");
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

    // ---------------------------------------------------------------------
    // Paso "buscar imagen": se captura la pantalla, el usuario recorta el
    // botón y al reproducir se toca donde aparezca ese recorte.

    private void startImageStep() {
        if (!recording || recSteps == null || injecting || cropping) return;
        if (Build.VERSION.SDK_INT < 30) {
            toast("La búsqueda por imagen necesita Android 11 o más nuevo");
            return;
        }
        cropping = true;
        final long pressedAt = SystemClock.uptimeMillis();
        // Se oculta la capa de grabación para que no salga en la captura.
        setRecordingUiVisible(false);
        handler.postDelayed(() -> capture((bmp, err) -> handler.post(() -> {
            if (!recording || !cropping) {
                if (bmp != null) bmp.recycle();
                return;
            }
            if (bmp == null) {
                toast("No se pudo capturar la pantalla (" + captureError(err) + ")");
                endCrop();
                return;
            }
            showCrop(bmp, pressedAt);
        })), 350);
    }

    private void showCrop(final Bitmap shot, final long pressedAt) {
        cropView = new CropOverlay(this, shot, new CropOverlay.Listener() {
            @Override
            public void onCrop(Rect r) {
                String png = null;
                try {
                    Bitmap piece = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height());
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    piece.compress(Bitmap.CompressFormat.PNG, 100, out);
                    png = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                } catch (Exception ignored) {
                }
                float cx = r.exactCenterX(), cy = r.exactCenterY();
                endCrop();
                if (png == null) {
                    toast("No se pudo guardar el recorte");
                    return;
                }
                recSteps.add(Macro.Step.image(Math.max(0, pressedAt - lastEventEnd), png));
                lastEventEnd = SystemClock.uptimeMillis();
                updateCounter();
                // Se toca ahí ahora también, para seguir grabando lo que viene después.
                // (con una pequeña espera, para que la pantalla de recorte ya no esté).
                injecting = true;
                final Macro.Step tap = Macro.Step.gesture(0, new float[]{cx, cy, 0, cx, cy, 60});
                handler.postDelayed(() -> {
                    if (recording) injectDuringRecording(tap);
                    else injecting = false;
                }, 300);
            }

            @Override
            public void onCancel() {
                endCrop();
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(cropView, lp);
        } catch (Exception ex) {
            cropView = null;
            toast("No se pudo abrir el recorte: " + ex.getMessage());
            endCrop();
        }
    }

    private void endCrop() {
        if (cropView != null) {
            try { wm.removeViewImmediate(cropView); } catch (Exception ignored) { }
            cropView = null;
        }
        cropping = false;
        if (recording) setRecordingUiVisible(true);
    }

    private void setRecordingUiVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (touchLayer != null) touchLayer.setVisibility(v);
        if (bar != null) bar.setVisibility(v);
        setLayerTouchable(visible);
    }

    private interface ShotCallback {
        /** Se llama en el hilo de fondo. bmp es null si falló (err = código). */
        void onShot(Bitmap bmp, int err);
    }

    private void capture(final ShotCallback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onShot(null, -3);
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, bg, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult res) {
                    Bitmap out = null;
                    try (HardwareBuffer hb = res.getHardwareBuffer()) {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                        if (hw != null) {
                            out = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                        }
                    } catch (Throwable ignored) {
                    }
                    cb.onShot(out, out == null ? -1 : 0);
                }

                @Override
                public void onFailure(int errorCode) {
                    cb.onShot(null, errorCode);
                }
            });
        } catch (Throwable t) {
            cb.onShot(null, -2);
        }
    }

    private static String captureError(int code) {
        switch (code) {
            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
            case -2:
                return "sin permiso: desactivá y volvé a activar AutoToques en Accesibilidad";
            case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                return "la app no permite capturas de pantalla";
            case -3:
                return "necesita Android 11 o más nuevo";
            default:
                return "error " + code;
        }
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
        b.addView(barButton("🎯", v -> startImageStep()));
        b.addView(barButton("✔", v -> stopRecording(true)));
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
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
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
            if (cropView != null) try { wm.removeView(cropView); } catch (Exception ignored) { }
        }
        cropView = null;
        cropping = false;
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
        Waker.acquire(this, m.maxMs() + 60_000);
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
        Waker.acquire(this, m.maxMs() + 60_000);
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
        if (s.type == Macro.Step.IMAGE) {
            searchImage(token, s, SystemClock.uptimeMillis() + s.timeoutSec * 1000L, 0, 0);
            return;
        }
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

    /**
     * Captura la pantalla y busca el recorte. Si no está, reintenta hasta que
     * se cumpla el tiempo de espera (sirve también para esperar a que cargue).
     */
    private void searchImage(final int token, final Macro.Step s, final long deadline,
                             final int bestPct, final int lastErr) {
        if (token != runToken || !playing) return;
        if (tplStep != s) {
            tplStep = null;
            tplPx = null;
            try {
                byte[] raw = Base64.decode(s.img, Base64.DEFAULT);
                Bitmap t = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                tplW = t.getWidth();
                tplH = t.getHeight();
                tplPx = new int[tplW * tplH];
                t.getPixels(tplPx, 0, tplW, 0, 0, tplW, tplH);
                t.recycle();
                tplStep = s;
            } catch (Throwable e) {
                failPlayback("Falló en el paso " + (playIndex + 1) + ": el recorte guardado está dañado");
                return;
            }
        }
        final int[] tpl = tplPx;
        final int tw = tplW, th = tplH;
        capture((bmp, err) -> {
            ImageMatcher.Result found = null;
            if (bmp != null) {
                try {
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    found = ImageMatcher.find(px, w, h, tpl, tw, th);
                } catch (Throwable ignored) {
                }
                bmp.recycle();
            }
            final ImageMatcher.Result r = found;
            handler.post(() -> onSearchResult(token, s, deadline, bestPct, r, err, tw, th));
        });
    }

    private void onSearchResult(final int token, final Macro.Step s, final long deadline, int bestPct,
                                ImageMatcher.Result r, int err, int tw, int th) {
        if (token != runToken || !playing) return;
        if (r != null && r.found) {
            float cx = r.x + tw / 2f, cy = r.y + th / 2f;
            Macro.Step tap = Macro.Step.gesture(0, new float[]{cx, cy, 0, cx, cy, 60});
            boolean ok;
            try {
                ok = dispatchGesture(buildGesture(tap), new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) { next(token); }
                    @Override public void onCancelled(GestureDescription g) { next(token); }
                }, handler);
            } catch (Exception ex) {
                ok = false;
            }
            if (!ok) next(token);
            return;
        }
        final int pct = Math.max(bestPct, r == null ? 0 : r.percent());
        final int lastErr = r == null ? err : 0;
        if (SystemClock.uptimeMillis() < deadline) {
            handler.postDelayed(() -> searchImage(token, s, deadline, pct, lastErr), 700);
            return;
        }
        String why = r == null
                ? "no se pudo capturar la pantalla (" + captureError(err) + ")"
                : "no apareció la imagen en " + s.timeoutSec + " s (parecido máximo " + pct + "%)";
        failPlayback("Falló en el paso " + (playIndex + 1) + ": " + why);
    }

    /** Corta la reproducción y avisa con una notificación. */
    private void failPlayback(String msg) {
        String name = playMacro == null ? "" : playMacro.name;
        Notif.alert(this, "«" + name + "» no terminó", msg);
        finishPlayback(msg);
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
        tplStep = null;
        tplPx = null;
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
