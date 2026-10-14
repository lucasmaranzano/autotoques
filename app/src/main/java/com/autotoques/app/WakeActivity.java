package com.autotoques.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * Pantalla invisible que enciende el celular y quita la pantalla de bloqueo
 * (si no tiene PIN/patrón). Después avisa al servicio para empezar.
 */
public class WakeActivity extends Activity {

    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { proceed(); }
                @Override public void onDismissError() { proceed(); }
                @Override public void onDismissCancelled() { proceed(); }
            });
            // Por si el sistema no responde.
            getWindow().getDecorView().postDelayed(this::proceed, 2500);
        } else {
            getWindow().getDecorView().postDelayed(this::proceed, 300);
        }
    }

    private void proceed() {
        if (done) return;
        done = true;
        MacroService s = MacroService.instance;
        if (s != null) s.onUnlocked();
        finish();
        overridePendingTransition(0, 0);
    }
}
