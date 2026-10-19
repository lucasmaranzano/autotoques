package com.autotoques.app;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Guarda las grabaciones en un archivo JSON privado de la app. */
public final class MacroStore {

    private static final String FILE = "macros.json";

    private MacroStore() {}

    public static synchronized List<Macro> load(Context ctx) {
        List<Macro> list = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONArray a = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                try {
                    list.add(Macro.fromJson(a.getJSONObject(i)));
                } catch (Exception ignored) {
                    // entrada dañada: se saltea
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static synchronized void save(Context ctx, List<Macro> list) {
        try {
            JSONArray a = new JSONArray();
            for (Macro m : list) a.put(m.toJson());
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            File dest = new File(ctx.getFilesDir(), FILE);
            if (!tmp.renameTo(dest)) {
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    out.write(a.toString().getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized Macro get(Context ctx, String id) {
        if (id == null) return null;
        for (Macro m : load(ctx)) if (id.equals(m.id)) return m;
        return null;
    }

    public static synchronized void upsert(Context ctx, Macro macro) {
        List<Macro> list = load(ctx);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(macro.id)) {
                list.set(i, macro);
                found = true;
                break;
            }
        }
        if (!found) list.add(macro);
        save(ctx, list);
    }

    /** Agrega una grabación justo debajo de otra (o al final si no la encuentra). */
    public static synchronized void insertAfter(Context ctx, String afterId, Macro macro) {
        List<Macro> list = load(ctx);
        int pos = list.size();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(afterId)) {
                pos = i + 1;
                break;
            }
        }
        list.add(pos, macro);
        save(ctx, list);
    }

    public static synchronized boolean nameExists(Context ctx, String name) {
        for (Macro m : load(ctx)) if (m.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    public static synchronized void delete(Context ctx, String id) {
        List<Macro> list = load(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        save(ctx, list);
    }
}
