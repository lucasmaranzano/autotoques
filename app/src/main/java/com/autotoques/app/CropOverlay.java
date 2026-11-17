package com.autotoques.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pantalla de recorte: muestra la captura y deja marcar con el dedo el
 * rectángulo (el botón) que después se va a buscar al reproducir.
 */
public class CropOverlay extends FrameLayout {

    public interface Listener {
        void onCrop(Rect rect);
        void onCancel();
    }

    /** Lado mínimo del recorte, en píxeles. */
    public static final int MIN_SIDE = 32;

    private final Bitmap shot;
    private final Listener listener;
    private final Rect sel = new Rect();
    private boolean hasSel;
    private float downX, downY;

    private final Paint dim = new Paint();
    private final Paint border = new Paint();
    private final Paint cross = new Paint();
    private final int[] loc = new int[2];

    private final LinearLayout panel;
    private final TextView hint;
    private final TextView use;
    private final LayoutParams panelLp;

    public CropOverlay(Context ctx, Bitmap shot, Listener listener) {
        super(ctx);
        this.shot = shot;
        this.listener = listener;
        setWillNotDraw(false);

        dim.setColor(0x99000000);
        border.setColor(0xFFFFD600);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(2));
        cross.setColor(0xFFFF5252);
        cross.setStrokeWidth(dp(2));

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0202124);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);

        hint = new TextView(ctx);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setText("Marcá con el dedo un rectángulo sobre el botón que hay que tocar. "
                + "Que entre el botón justo, sin fondo de más.");
        panel.addView(hint);

        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.END);
        TextView cancel = button("Cancelar", 0xFFBDBDBD);
        cancel.setOnClickListener(v -> listener.onCancel());
        use = button("Usar este recorte", 0xFFFFD600);
        use.setOnClickListener(v -> {
            if (hasSel) listener.onCrop(new Rect(sel));
        });
        use.setAlpha(0.35f);
        row.addView(cancel);
        row.addView(use);
        panel.addView(row);

        panelLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        panelLp.setMargins(dp(12), dp(72), dp(12), dp(72));
        addView(panel, panelLp);
    }

    private TextView button(String text, int color) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(16);
        t.setPadding(dp(14), dp(12), dp(14), dp(6));
        return t;
    }

    @Override
    protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        c.save();
        // La captura se dibuja en coordenadas de pantalla: píxel de la imagen = píxel real.
        c.translate(-loc[0], -loc[1]);
        c.drawBitmap(shot, 0, 0, null);
        int w = shot.getWidth(), h = shot.getHeight();
        if (!hasSel) {
            c.drawRect(0, 0, w, h, dim);
        } else {
            c.drawRect(0, 0, w, sel.top, dim);
            c.drawRect(0, sel.bottom, w, h, dim);
            c.drawRect(0, sel.top, sel.left, sel.bottom, dim);
            c.drawRect(sel.right, sel.top, w, sel.bottom, dim);
            c.drawRect(sel, border);
            // Cruz roja = donde se va a tocar.
            float cx = sel.exactCenterX(), cy = sel.exactCenterY(), r = dp(7);
            c.drawLine(cx - r, cy, cx + r, cy, cross);
            c.drawLine(cx, cy - r, cx, cy + r, cross);
        }
        c.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getRawX(), y = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                hasSel = false;
                panel.setVisibility(INVISIBLE);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                setSel(downX, downY, x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setSel(downX, downY, x, y);
                if (sel.width() < MIN_SIDE || sel.height() < MIN_SIDE) {
                    hasSel = false;
                    hint.setText("Muy chico. Marcá un rectángulo más grande sobre el botón.");
                } else {
                    hint.setText("La cruz roja marca dónde se va a tocar. "
                            + "Si no quedó bien, marcá de nuevo.");
                }
                use.setAlpha(hasSel ? 1f : 0.35f);
                // El panel se corre para no tapar lo que se marcó.
                boolean selInLowerHalf = hasSel && sel.centerY() > shot.getHeight() / 2;
                panelLp.gravity = selInLowerHalf ? Gravity.TOP : Gravity.BOTTOM;
                panel.setLayoutParams(panelLp);
                panel.setVisibility(VISIBLE);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void setSel(float x0, float y0, float x1, float y1) {
        int w = shot.getWidth(), h = shot.getHeight();
        int l = clamp(Math.round(Math.min(x0, x1)), 0, w);
        int r = clamp(Math.round(Math.max(x0, x1)), 0, w);
        int t = clamp(Math.round(Math.min(y0, y1)), 0, h);
        int b = clamp(Math.round(Math.max(y0, y1)), 0, h);
        sel.set(l, t, r, b);
        hasSel = r > l && b > t;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
