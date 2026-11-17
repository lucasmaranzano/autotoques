package com.autotoques.app;

/**
 * Busca una imagen chica (el recorte) dentro de una captura de pantalla.
 * Java puro, sin librerías: primero busca en baja resolución (rápido) y
 * después afina los mejores candidatos a resolución completa.
 */
public final class ImageMatcher {

    /** Parecido mínimo (0..1) para aceptar una coincidencia. */
    public static final double MIN_SIMILARITY = 0.90;
    /** Correlación mínima de la forma (evita confundir el recorte con un fondo liso). */
    public static final double MIN_CORRELATION = 0.70;

    private static final int TOP = 8;

    private ImageMatcher() {}

    public static final class Result {
        public boolean found;
        /** Esquina superior izquierda de la mejor coincidencia. */
        public int x, y;
        /** Parecido de color, 0..1. */
        public double similarity;
        /** Correlación de forma, -1..1. */
        public double correlation;

        public int percent() {
            double v = Math.min(similarity, Math.max(0, correlation));
            return (int) Math.round(Math.max(0, v) * 100);
        }
    }

    /** screen y tpl son píxeles ARGB (como los de Bitmap.getPixels). */
    public static Result find(int[] screen, int sw, int sh, int[] tpl, int tw, int th) {
        Result r = new Result();
        if (tw < 4 || th < 4 || tw > sw || th > sh) return r;

        int[] sg = gray(screen);
        int[] tg = gray(tpl);

        // Factor de reducción: el recorte queda de ~500 píxeles en la pasada rápida.
        int f = (int) Math.round(Math.sqrt(tw * (double) th / 500.0));
        f = Math.max(1, Math.min(8, f));
        while (f > 1 && (tw / f < 6 || th / f < 6)) f--;

        int csw = sw / f, csh = sh / f, ctw = tw / f, cth = th / f;
        int[] cs = f == 1 ? sg : shrink(sg, sw, csw, csh, f);
        int[] ct = f == 1 ? tg : shrink(tg, tw, ctw, cth, f);

        // --- Pasada rápida: los TOP mejores por suma de diferencias ---
        long[] bestSad = new long[TOP];
        int[] bestX = new int[TOP], bestY = new int[TOP];
        java.util.Arrays.fill(bestSad, Long.MAX_VALUE);
        int maxX = csw - ctw, maxY = csh - cth;
        for (int y = 0; y <= maxY; y++) {
            for (int x = 0; x <= maxX; x++) {
                long limit = bestSad[TOP - 1];
                long sad = 0;
                for (int ty = 0; ty < cth && sad < limit; ty++) {
                    int so = (y + ty) * csw + x, to = ty * ctw;
                    for (int tx = 0; tx < ctw; tx++) {
                        int d = cs[so + tx] - ct[to + tx];
                        sad += d < 0 ? -d : d;
                    }
                }
                if (sad < limit) {
                    int i = TOP - 1;
                    while (i > 0 && bestSad[i - 1] > sad) {
                        bestSad[i] = bestSad[i - 1];
                        bestX[i] = bestX[i - 1];
                        bestY[i] = bestY[i - 1];
                        i--;
                    }
                    bestSad[i] = sad;
                    bestX[i] = x;
                    bestY[i] = y;
                }
            }
        }

        // --- Afinado a resolución completa alrededor de cada candidato ---
        int step = Math.max(1, Math.min(tw, th) / 48);
        long best = Long.MAX_VALUE;
        int bx = -1, by = -1;
        for (int k = 0; k < TOP; k++) {
            if (bestSad[k] == Long.MAX_VALUE) break;
            int cx = bestX[k] * f, cy = bestY[k] * f;
            for (int y = Math.max(0, cy - f); y <= Math.min(sh - th, cy + f); y++) {
                for (int x = Math.max(0, cx - f); x <= Math.min(sw - tw, cx + f); x++) {
                    long sad = 0;
                    for (int ty = 0; ty < th && sad < best; ty += step) {
                        int so = (y + ty) * sw + x, to = ty * tw;
                        for (int tx = 0; tx < tw; tx += step) {
                            int d = sg[so + tx] - tg[to + tx];
                            sad += d < 0 ? -d : d;
                        }
                    }
                    if (sad < best) {
                        best = sad;
                        bx = x;
                        by = y;
                    }
                }
            }
        }
        if (bx < 0) return r;

        // --- Puntaje final en el mejor lugar ---
        long diff = 0;
        double sumS = 0, sumT = 0, sumSS = 0, sumTT = 0, sumST = 0;
        int n = tw * th;
        for (int ty = 0; ty < th; ty++) {
            int so = (by + ty) * sw + bx, to = ty * tw;
            for (int tx = 0; tx < tw; tx++) {
                int a = screen[so + tx], b = tpl[to + tx];
                diff += Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                        + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                        + Math.abs((a & 255) - (b & 255));
                int gs = sg[so + tx], gt = tg[to + tx];
                sumS += gs;
                sumT += gt;
                sumSS += gs * (double) gs;
                sumTT += gt * (double) gt;
                sumST += gs * (double) gt;
            }
        }
        double varS = sumSS / n - (sumS / n) * (sumS / n);
        double varT = sumTT / n - (sumT / n) * (sumT / n);
        double cov = sumST / n - (sumS / n) * (sumT / n);
        r.x = bx;
        r.y = by;
        r.similarity = 1.0 - diff / (3.0 * n) / 255.0;
        if (varT < 16) {
            // Recorte casi liso: no hay forma que comparar, vale solo el color.
            r.correlation = r.similarity;
        } else {
            r.correlation = varS < 1e-6 ? 0 : cov / Math.sqrt(varS * varT);
        }
        r.found = r.similarity >= MIN_SIMILARITY && r.correlation >= MIN_CORRELATION;
        return r;
    }

    private static int[] gray(int[] argb) {
        int[] g = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            g[i] = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
        }
        return g;
    }

    /** Reduce promediando bloques de f×f. */
    private static int[] shrink(int[] g, int w, int cw, int ch, int f) {
        int[] out = new int[cw * ch];
        int area = f * f;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int sum = 0;
                for (int dy = 0; dy < f; dy++) {
                    int o = (y * f + dy) * w + x * f;
                    for (int dx = 0; dx < f; dx++) sum += g[o + dx];
                }
                out[y * cw + x] = sum / area;
            }
        }
        return out;
    }
}
