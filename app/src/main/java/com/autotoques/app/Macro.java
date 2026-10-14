package com.autotoques.app;

import android.accessibilityservice.AccessibilityService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Una grabación: lista de pasos + su programación. */
public class Macro {

    public String id;
    public String name;
    public long createdAt;
    public final List<Step> steps = new ArrayList<>();

    // Programación
    public boolean scheduleEnabled = false;
    public int hour = 8;
    public int minute = 0;
    /** bit 0 = lunes ... bit 6 = domingo. 0 = una sola vez. */
    public int daysMask = 0;
    public boolean goHomeFirst = true;
    public int initialDelaySec = 3;
    public int extraPauseMs = 0;

    // Último resultado
    public long lastRun = 0;
    public String lastResult = "";

    public static Macro create(String name) {
        Macro m = new Macro();
        m.id = Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(new Random().nextInt(1296), 36);
        m.name = name;
        m.createdAt = System.currentTimeMillis();
        return m;
    }

    /** Duración aproximada de una reproducción completa, en ms. */
    public long estimateMs() {
        long total = initialDelaySec * 1000L + (goHomeFirst ? 800 : 0);
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (i > 0) total += s.delayBefore + extraPauseMs;
            total += s.durationMs();
        }
        return total;
    }

    public static class Step {
        public static final int GESTURE = 0;
        public static final int BACK = 1;
        public static final int HOME = 2;
        public static final int RECENTS = 3;

        public int type;
        /** Espera (ms) entre el fin del paso anterior y el comienzo de este. */
        public long delayBefore;
        /** Puntos del trazo: x, y, t (ms desde que apoyó el dedo). Solo para GESTURE. */
        public float[] pts = new float[0];

        public static Step gesture(long delayBefore, float[] pts) {
            Step s = new Step();
            s.type = GESTURE;
            s.delayBefore = delayBefore;
            s.pts = pts;
            return s;
        }

        public static Step action(int type, long delayBefore) {
            Step s = new Step();
            s.type = type;
            s.delayBefore = delayBefore;
            return s;
        }

        public long durationMs() {
            if (type != GESTURE || pts.length < 3) return 300;
            return (long) pts[pts.length - 1];
        }

        public int globalAction() {
            switch (type) {
                case BACK: return AccessibilityService.GLOBAL_ACTION_BACK;
                case RECENTS: return AccessibilityService.GLOBAL_ACTION_RECENTS;
                case HOME:
                default: return AccessibilityService.GLOBAL_ACTION_HOME;
            }
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("t", type);
            o.put("d", delayBefore);
            if (type == GESTURE) {
                JSONArray a = new JSONArray();
                for (float v : pts) a.put(Math.round(v * 10f) / 10.0);
                o.put("p", a);
            }
            return o;
        }

        static Step fromJson(JSONObject o) throws JSONException {
            Step s = new Step();
            s.type = o.optInt("t", GESTURE);
            s.delayBefore = o.optLong("d", 0);
            JSONArray a = o.optJSONArray("p");
            if (a != null) {
                s.pts = new float[a.length()];
                for (int i = 0; i < a.length(); i++) s.pts[i] = (float) a.getDouble(i);
            }
            return s;
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("createdAt", createdAt);
        o.put("scheduleEnabled", scheduleEnabled);
        o.put("hour", hour);
        o.put("minute", minute);
        o.put("daysMask", daysMask);
        o.put("goHomeFirst", goHomeFirst);
        o.put("initialDelaySec", initialDelaySec);
        o.put("extraPauseMs", extraPauseMs);
        o.put("lastRun", lastRun);
        o.put("lastResult", lastResult);
        JSONArray a = new JSONArray();
        for (Step s : steps) a.put(s.toJson());
        o.put("steps", a);
        return o;
    }

    static Macro fromJson(JSONObject o) throws JSONException {
        Macro m = new Macro();
        m.id = o.getString("id");
        m.name = o.optString("name", "Sin nombre");
        m.createdAt = o.optLong("createdAt", 0);
        m.scheduleEnabled = o.optBoolean("scheduleEnabled", false);
        m.hour = o.optInt("hour", 8);
        m.minute = o.optInt("minute", 0);
        m.daysMask = o.optInt("daysMask", 0);
        m.goHomeFirst = o.optBoolean("goHomeFirst", true);
        m.initialDelaySec = o.optInt("initialDelaySec", 3);
        m.extraPauseMs = o.optInt("extraPauseMs", 0);
        m.lastRun = o.optLong("lastRun", 0);
        m.lastResult = o.optString("lastResult", "");
        JSONArray a = o.optJSONArray("steps");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) m.steps.add(Step.fromJson(a.getJSONObject(i)));
        }
        return m;
    }
}
