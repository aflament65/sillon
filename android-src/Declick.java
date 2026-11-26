package fr.aflament.sillon;

import java.util.Arrays;

/* Nettoyage des craquements d'un vinyle (clics) — indépendant d'Android, testé sur ordinateur.
   Détection : un clic est une pointe très brève dont la « dérivée seconde » dépasse de loin le niveau
   habituel du passage ET le niveau juste autour (une cymbale ou une attaque de batterie, elles, durent).
   Réparation : les échantillons abîmés sont recalculés par prédiction linéaire (modèle autorégressif
   appris sur le son d'avant et d'après), la méthode classique de restauration des disques.
   Zones de plus de 2,5 ms : jamais touchées. Même algorithme que la page (index.html, declickJS). */
final class Declick {
    static double factor(int level) { return level >= 2 ? 6.0 : 9.0; }
    static final int P = 24;

    static int channel(float[] x, int n, int from, int to, int level, int rate) {
        if (level <= 0 || n < 256) return 0;
        final double k = factor(level);
        final int W = 512, maxLen = Math.max(8, (int) Math.round(rate * 0.0025)), join = Math.max(4, rate / 4000);
        float[] d = new float[n];
        for (int i = 2; i < n; i++) d[i] = Math.abs(x[i] - 2 * x[i - 1] + x[i - 2]);
        int nw = (n + W - 1) / W;
        float[] med = new float[nw], tmp = new float[W];
        for (int w = 0; w < nw; w++) {
            int a = w * W, b = Math.min(n, a + W), m = b - a;
            System.arraycopy(d, a, tmp, 0, m);
            Arrays.sort(tmp, 0, m);
            med[w] = tmp[m / 2];
        }
        int count = 0, i = Math.max(P + 8, from);
        final int end = Math.min(n - P - 8, to);
        while (i < end) {
            int w = i / W;
            float lv = Math.max(med[w], Math.max(w > 0 ? med[w - 1] : 0, w + 1 < nw ? med[w + 1] : 0));
            double thr = k * 1.4826 * Math.max(lv, 2.5f);
            if (d[i] <= thr || d[i] < 150) { i++; continue; }
            /* étendue : pointes au-dessus du quart du seuil, réunies si proches, dans les deux sens */
            double lo = thr * 0.25;
            int s = i, e = i;
            for (int j = i + 1; j < n && j - e <= join; j++) if (d[j] > lo) e = j;
            for (int j = i - 1; j >= 0 && s - j <= join; j--) if (d[j] > lo) s = j;
            int a = Math.max(P + 2, s - 3), b = Math.min(n - P - 3, e + 2);
            if (b - a + 1 > maxLen) { i = e + 1; continue; }
            /* isolé ? autour (64 échantillons de chaque côté) le son doit être bien plus calme */
            double around = 0; int na = 0;
            for (int j = Math.max(2, a - 64); j < a - 2; j++) { around += d[j]; na++; }
            for (int j = b + 3; j < Math.min(n, b + 67); j++) { around += d[j]; na++; }
            around /= Math.max(1, na);
            double peak = 0; for (int j = s; j <= e; j++) peak = Math.max(peak, d[j]);
            if (peak < around * 8) { i = e + 1; continue; }
            if (arRepair(x, n, a, b)) count++;
            for (int j = Math.max(2, a); j < Math.min(n, b + 3); j++) d[j] = Math.abs(x[j] - 2 * x[j - 1] + x[j - 2]);
            i = b + 3;
        }
        return count;
    }

    /* Interpolation autorégressive : x[a..b] inconnus, modèle d'ordre P appris sur le contexte de part et d'autre */
    static boolean arRepair(float[] x, int n, int a, int b) {
        final int L = b - a + 1, ctx = 384;
        int c0 = Math.max(0, a - ctx), c1 = Math.min(n, b + 1 + ctx);
        /* autocorrélation sur les deux côtés connus (sans la zone abîmée) */
        double[] r = new double[P + 1];
        for (int seg = 0; seg < 2; seg++) {
            int s0 = seg == 0 ? c0 : b + 1, s1 = seg == 0 ? a : c1;
            for (int lag = 0; lag <= P; lag++) {
                double acc = 0;
                for (int t = s0 + lag; t < s1; t++) acc += (double) x[t] * x[t - lag];
                r[lag] += acc;
            }
        }
        if (r[0] <= 1e-9) { linear(x, a, b); return true; }
        r[0] *= 1.0001; /* un rien de stabilité */
        double[] ar = levinson(r);
        if (ar == null) { linear(x, a, b); return true; }
        /* M = autocorrélation de ar (Toeplitz) ; second membre à partir des échantillons connus */
        double[] ra = new double[L];
        for (int m = 0; m < L; m++) { double acc = 0; for (int q = 0; q + m <= P; q++) acc += ar[q] * ar[q + m]; ra[m] = acc; }
        double[][] M = new double[L][L];
        for (int p = 0; p < L; p++) for (int q = 0; q < L; q++) M[p][q] = ra[Math.abs(p - q)];
        double[] rhs = new double[L];
        for (int t = a; t <= b + P; t++) {
            double ct = 0;
            for (int q = 0; q <= P; q++) { int u = t - q; if (u < a || u > b) ct += ar[q] * x[u]; }
            for (int q = 0; q <= P; q++) { int u = t - q; if (u >= a && u <= b) rhs[u - a] -= ar[q] * ct; }
        }
        double[] sol = cholesky(M, rhs);
        if (sol == null) { linear(x, a, b); return true; }
        for (int p = 0; p < L; p++) x[a + p] = (float) Math.max(-32768, Math.min(32767, sol[p]));
        return true;
    }

    static double[] levinson(double[] r) {
        double[] a = new double[P + 1], tmp = new double[P + 1];
        a[0] = 1;
        double err = r[0];
        for (int i = 1; i <= P; i++) {
            double acc = r[i];
            for (int j = 1; j < i; j++) acc += a[j] * r[i - j];
            double kk = -acc / err;
            System.arraycopy(a, 0, tmp, 0, i);
            for (int j = 1; j < i; j++) a[j] = tmp[j] + kk * tmp[i - j];
            a[i] = kk;
            err *= (1 - kk * kk);
            if (err <= 0) return null;
        }
        return a;
    }

    static double[] cholesky(double[][] M, double[] y) {
        int n = y.length;
        double[][] Lm = new double[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
            double s = M[i][j];
            for (int k = 0; k < j; k++) s -= Lm[i][k] * Lm[j][k];
            if (i == j) { if (s <= 0) return null; Lm[i][i] = Math.sqrt(s); } else Lm[i][j] = s / Lm[j][j];
        }
        double[] z = new double[n];
        for (int i = 0; i < n; i++) { double s = y[i]; for (int k = 0; k < i; k++) s -= Lm[i][k] * z[k]; z[i] = s / Lm[i][i]; }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) { double s = z[i]; for (int k = i + 1; k < n; k++) s -= Lm[k][i] * x[k]; x[i] = s / Lm[i][i]; }
        return x;
    }

    static void linear(float[] x, int a, int b) {
        float p0 = x[a - 1], p1 = x[b + 1];
        for (int i = a; i <= b; i++) x[i] = p0 + (p1 - p0) * (i - a + 1) / (float) (b - a + 2);
    }

    /* son entrelacé stéréo 16 bits : nettoie les trames [from, to) d'un tampon de n trames (contexte autour) */
    static int stereo(short[] s, int n, int from, int to, int level, int rate) {
        if (level <= 0) return 0;
        float[] c = new float[n];
        int total = 0;
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < n; i++) c[i] = s[2 * i + ch];
            total = Math.max(total, channel(c, n, from, to, level, rate));
            for (int i = from; i < to; i++) s[2 * i + ch] = (short) Math.round(c[i]);
        }
        return total;
    }
}
