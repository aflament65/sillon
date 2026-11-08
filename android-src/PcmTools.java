package fr.aflament.sillon;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/* Son brut (16 bits, stéréo entrelacé, petit-boutiste) : écriture, volume toutes les 50 ms, découpe et encodage MP3.
   Indépendant d'Android : testé tel quel sur ordinateur. */
final class PcmTools {
    interface Prog { void p(double f); }

    /* Reçoit le son décodé : l'écrit dans un fichier ou l'encode directement, et mesure le volume */
    static class Sink {
        int rate = 44100;
        long frames = 0;
        int peak = 0;
        double hopSec = 0.05;
        private int hop = 2205, cnt = 0;
        private double acc = 0;
        float[] env = new float[1 << 14];
        int envN = 0;
        private OutputStream file;
        private byte[] bb = new byte[0];
        /* encodage direct (conversion d'un fichier entier) */
        private long lame = 0;
        private OutputStream mp3;
        private byte[] mp3buf = new byte[0];

        Sink toFile(File f) throws IOException { file = new BufferedOutputStream(new FileOutputStream(f), 1 << 20); return this; }
        Sink toMp3(OutputStream os) { mp3 = os; return this; }

        void format(int r) throws IOException {
            if (frames > 0) return; /* le débit ne change pas en cours de route */
            rate = r;
            hop = Math.max(1, (int) Math.round(r * hopSec));
            if (mp3 != null) {
                if (lame != 0) Lame.close(lame);
                lame = Lame.init(r, 2, 44100, 320);
                if (lame == 0) throw new IOException("Encodeur MP3 indisponible");
            }
        }

        /* bloc stéréo entrelacé de n trames */
        void block(short[] s, int n) throws IOException {
            for (int k = 0; k < n; k++) {
                int l = s[2 * k], r = s[2 * k + 1];
                int al = l < 0 ? -l : l, ar = r < 0 ? -r : r;
                if (al > peak) peak = al;
                if (ar > peak) peak = ar;
                double fl = l / 32768.0, fr = r / 32768.0;
                acc += (fl * fl + fr * fr) / 2;
                if (++cnt == hop) {
                    if (envN == env.length) { float[] e = new float[env.length * 2]; System.arraycopy(env, 0, e, 0, envN); env = e; }
                    env[envN++] = (float) (10 * Math.log10(acc / hop + 1e-12));
                    acc = 0; cnt = 0;
                }
            }
            if (file != null) {
                if (bb.length < n * 4) bb = new byte[n * 4];
                for (int k = 0; k < n * 2; k++) { bb[2 * k] = (byte) s[k]; bb[2 * k + 1] = (byte) (s[k] >> 8); }
                file.write(bb, 0, n * 4);
            }
            if (mp3 != null) {
                if (lame == 0) format(rate);
                int need = (int) (n * 1.25) + 7200;
                if (mp3buf.length < need) mp3buf = new byte[need];
                int w = Lame.encode(lame, s, n, mp3buf);
                if (w < 0) throw new IOException("Erreur d'encodage MP3 (" + w + ")");
                mp3.write(mp3buf, 0, w);
            }
            frames += n;
        }

        void finish() throws IOException {
            if (file != null) { file.flush(); file.close(); file = null; }
            if (mp3 != null && lame != 0) {
                byte[] out = new byte[7200];
                int w = Lame.flush(lame, out);
                if (w > 0) mp3.write(out, 0, w);
                Lame.close(lame);
                lame = 0;
            }
        }

        void abort() {
            try { if (file != null) file.close(); } catch (IOException e) { }
            if (lame != 0) { Lame.close(lame); lame = 0; }
        }

        byte[] envBytes() { /* Float32 petit-boutiste */
            byte[] b = new byte[envN * 4];
            for (int i = 0; i < envN; i++) {
                int v = Float.floatToIntBits(env[i]);
                b[4 * i] = (byte) v; b[4 * i + 1] = (byte) (v >> 8); b[4 * i + 2] = (byte) (v >> 16); b[4 * i + 3] = (byte) (v >> 24);
            }
            return b;
        }
    }

    /* Un morceau [start, end] (secondes) du fichier brut → MP3 320 kbit/s, avec volume et fondus de 30 ms */
    static void encodeRange(File pcm, int rate, double start, double end, double gain, byte[] tag, OutputStream os, Prog prog) throws IOException {
        long total = pcm.length() / 4;
        long a = Math.max(0, Math.round(start * rate)), b = Math.min(total, Math.round(end * rate));
        if (b <= a) throw new IOException("Morceau vide");
        int fade = (int) Math.round(rate * 0.03);
        long h = Lame.init(rate, 2, 44100, 320);
        if (h == 0) throw new IOException("Encodeur MP3 indisponible");
        try (RandomAccessFile f = new RandomAccessFile(pcm, "r")) {
            if (tag != null) os.write(tag);
            f.seek(a * 4);
            final int B = 16384;
            byte[] raw = new byte[B * 4];
            short[] s = new short[B * 2];
            byte[] out = new byte[(int) (B * 1.25) + 7200];
            long n = b - a, done = 0;
            while (done < n) {
                int k = (int) Math.min(B, n - done);
                f.readFully(raw, 0, k * 4);
                for (int i = 0; i < k; i++) {
                    long pos = done + i;
                    double g = gain;
                    if (pos < fade) g *= pos / (double) fade;
                    else if (n - pos <= fade) g *= (n - pos - 1) / (double) fade;
                    for (int c = 0; c < 2; c++) {
                        int v = (short) ((raw[4 * i + 2 * c] & 255) | (raw[4 * i + 2 * c + 1] << 8));
                        double x = v * g;
                        s[2 * i + c] = (short) (x > 32767 ? 32767 : x < -32768 ? -32768 : x);
                    }
                }
                int w = Lame.encode(h, s, k, out);
                if (w < 0) throw new IOException("Erreur d'encodage MP3 (" + w + ")");
                os.write(out, 0, w);
                done += k;
                if (prog != null) prog.p(done / (double) n);
            }
            int w = Lame.flush(h, out);
            if (w > 0) os.write(out, 0, w);
        } finally {
            Lame.close(h);
        }
    }

    /* Extrait [start, end] : stéréo au débit d'origine, ou mono 11 025 Hz (empreinte sonore) */
    static byte[] readRange(File pcm, int rate, double start, double end, boolean mono11k) throws IOException {
        long total = pcm.length() / 4;
        long a = Math.max(0, Math.round(start * rate)), b = Math.min(total, Math.round(end * rate));
        if (b <= a) return new byte[0];
        try (RandomAccessFile f = new RandomAccessFile(pcm, "r")) {
            f.seek(a * 4);
            byte[] raw = new byte[(int) ((b - a) * 4)];
            f.readFully(raw);
            if (!mono11k) return raw;
            double step = rate / 11025.0;
            int m = (int) Math.floor((b - a) / step);
            byte[] out = new byte[m * 2];
            for (int i = 0; i < m; i++) {
                int j = (int) (i * step);
                int l = (short) ((raw[4 * j] & 255) | (raw[4 * j + 1] << 8)), r = (short) ((raw[4 * j + 2] & 255) | (raw[4 * j + 3] << 8));
                int v = (l + r) / 2;
                out[2 * i] = (byte) v; out[2 * i + 1] = (byte) (v >> 8);
            }
            return out;
        }
    }
}
