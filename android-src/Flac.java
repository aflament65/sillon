package fr.aflament.sillon;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/* Encodeur FLAC (sans perte) 16 bits stéréo — indépendant d'Android, vérifié sur ordinateur avec ffmpeg.
   Prédicteurs fixes (ordre 0 à 4), codage de Rice partitionné, et le meilleur des 4 modes stéréo
   (gauche/droite, gauche/différence, différence/droite, milieu/différence) pour chaque bloc de 4096 trames.
   Étiquettes Vorbis (titre, artiste, album…) et pochette dans le fichier, comme les MP3. */
final class Flac {
    private static final int BS = 4096;
    private final OutputStream os;
    private final int rate;
    private final short[] pend = new short[BS * 2];
    private int pendN = 0;
    private long frameNo = 0;

    Flac(OutputStream os, int rate, long totalFrames, Map<String, String> tags, byte[] picture, String mime) throws IOException {
        this.os = os;
        this.rate = rate;
        os.write(new byte[]{'f', 'L', 'a', 'C'});
        /* STREAMINFO */
        byte[] si = new byte[34];
        put16(si, 0, BS); put16(si, 2, BS);
        /* tailles de trame min/max inconnues (0), débit 20 bits, canaux-1 3 bits, bits-1 5 bits, total 36 bits, MD5 inconnu */
        long v = ((long) rate << 44) | ((long) (2 - 1) << 41) | ((long) (16 - 1) << 36) | (totalFrames & 0xFFFFFFFFFL);
        for (int i = 0; i < 8; i++) si[10 + i] = (byte) (v >>> (56 - 8 * i));
        boolean hasPic = picture != null && picture.length > 0;
        block(0, si, false);
        /* commentaires Vorbis (longueurs en petit-boutiste) */
        java.io.ByteArrayOutputStream vc = new java.io.ByteArrayOutputStream();
        byte[] vendor = "Sillon".getBytes(StandardCharsets.UTF_8);
        le32(vc, vendor.length); vc.write(vendor);
        int n = 0;
        java.io.ByteArrayOutputStream list = new java.io.ByteArrayOutputStream();
        if (tags != null) for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            byte[] c = (e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8);
            le32(list, c.length); list.write(c); n++;
        }
        le32(vc, n); vc.write(list.toByteArray());
        block(4, vc.toByteArray(), !hasPic);
        if (hasPic) {
            byte[] m = (mime == null ? "image/jpeg" : mime).getBytes(StandardCharsets.US_ASCII);
            java.io.ByteArrayOutputStream pb = new java.io.ByteArrayOutputStream();
            be32(pb, 3); be32(pb, m.length); pb.write(m); be32(pb, 0);
            be32(pb, 0); be32(pb, 0); be32(pb, 0); be32(pb, 0);
            be32(pb, picture.length); pb.write(picture);
            block(6, pb.toByteArray(), true);
        }
    }

    private void block(int type, byte[] data, boolean last) throws IOException {
        os.write((last ? 0x80 : 0) | type);
        os.write(data.length >>> 16); os.write(data.length >>> 8); os.write(data.length);
        os.write(data);
    }
    private static void put16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }
    private static void le32(java.io.ByteArrayOutputStream o, int v) { o.write(v); o.write(v >>> 8); o.write(v >>> 16); o.write(v >>> 24); }
    private static void be32(java.io.ByteArrayOutputStream o, int v) { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v); }

    /* trames stéréo entrelacées */
    void write(short[] s, int frames) throws IOException {
        int i = 0;
        while (i < frames) {
            int k = Math.min(frames - i, BS - pendN);
            System.arraycopy(s, 2 * i, pend, 2 * pendN, 2 * k);
            pendN += k; i += k;
            if (pendN == BS) { frame(pend, BS); pendN = 0; }
        }
    }

    void finish() throws IOException {
        if (pendN > 0) { frame(pend, pendN); pendN = 0; }
        os.flush();
    }

    /* ---------- une trame ---------- */
    private final int[] L = new int[BS], R = new int[BS], M = new int[BS], Sd = new int[BS];
    private final Bits bw = new Bits();

    private void frame(short[] s, int n) throws IOException {
        for (int i = 0; i < n; i++) { int l = s[2 * i], r = s[2 * i + 1]; L[i] = l; R[i] = r; Sd[i] = l - r; M[i] = (l + r) >> 1; }
        Choice cl = best(L, n, 16), cr = best(R, n, 16), cs = best(Sd, n, 17), cm = best(M, n, 16);
        long ind = cl.bits + cr.bits, ls = cl.bits + cs.bits, rs = cs.bits + cr.bits, ms = cm.bits + cs.bits;
        int mode; Choice a, b; int[] xa, xb; int ba, bb;
        if (ind <= ls && ind <= rs && ind <= ms) { mode = 1; a = cl; b = cr; xa = L; xb = R; ba = 16; bb = 16; }
        else if (ls <= rs && ls <= ms) { mode = 8; a = cl; b = cs; xa = L; xb = Sd; ba = 16; bb = 17; }
        else if (rs <= ms) { mode = 9; a = cs; b = cr; xa = Sd; xb = R; ba = 17; bb = 16; }
        else { mode = 10; a = cm; b = cs; xa = M; xb = Sd; ba = 16; bb = 17; }
        bw.reset();
        bw.put(0xFFF8, 16);
        int bsCode = n == BS ? 12 : 7;
        int srCode = rate == 44100 ? 9 : rate == 48000 ? 10 : rate == 96000 ? 11 : rate == 88200 ? 0 : rate == 32000 ? 8 : rate == 22050 ? 6 : 0;
        bw.put(bsCode, 4); bw.put(srCode, 4); bw.put(mode, 4); bw.put(4, 3); bw.put(0, 1);
        utf8(bw, frameNo++);
        if (bsCode == 7) bw.put(n - 1, 16);
        bw.align();
        bw.put(crc8(bw.buf, bw.len()), 8);
        subframe(xa, n, ba, a);
        subframe(xb, n, bb, b);
        bw.align();
        int c16 = crc16(bw.buf, bw.len());
        bw.put(c16, 16);
        bw.align();
        os.write(bw.buf, 0, bw.len());
    }

    private static final class Choice { int order; long bits; int porder; int[] params = new int[256]; }

    /* résidu d'un prédicteur fixe d'ordre o */
    private static int res(int[] x, int i, int o) {
        switch (o) {
            case 0: return x[i];
            case 1: return x[i] - x[i - 1];
            case 2: return x[i] - 2 * x[i - 1] + x[i - 2];
            case 3: return x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3];
            default: return x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4];
        }
    }

    private final int[] ru = new int[BS];

    private Choice best(int[] x, int n, int bps) {
        Choice best = null;
        for (int o = 0; o <= 4 && o < n; o++) {
            for (int i = o; i < n; i++) { int r = res(x, i, o); ru[i] = (r << 1) ^ (r >> 31); }
            Choice c = new Choice();
            c.order = o;
            long rb = rice(ru, n, o, c);
            c.bits = 8 + (long) o * bps + rb;
            if (best == null || c.bits < best.bits) best = c;
        }
        /* bloc constant ou presque : le verbatim n'est jamais meilleur ici, sauf cas extrêmes */
        long verb = 8 + (long) n * bps;
        if (best.bits > verb) { best.order = -1; best.bits = verb; }
        return best;
    }

    /* meilleur ordre de partition (0..8) et paramètres de Rice ; renvoie le nombre de bits du résidu (estimation :
       n·(k+1) + somme/2^k, assez juste pour choisir, et rapide : les sommes ne sont calculées qu'une fois) */
    private final long[] psum = new long[256];
    private final int[] pcnt = new int[256];
    private long rice(int[] u, int n, int o, Choice c) {
        int maxP = 0;
        while (maxP < 8 && (n >> (maxP + 1)) > o && (n % (1 << (maxP + 1))) == 0) maxP++;
        int parts = 1 << maxP, plen = n >> maxP;
        for (int q = 0; q < parts; q++) {
            int s0 = q == 0 ? o : q * plen, s1 = (q + 1) * plen;
            long sum = 0;
            for (int i = s0; i < s1; i++) sum += u[i] & 0xFFFFFFFFL;
            psum[q] = sum; pcnt[q] = s1 - s0;
        }
        long bestBits = Long.MAX_VALUE;
        int[] params = new int[256];
        for (int p = maxP; p >= 0; p--) {
            int np = 1 << p;
            if (p < maxP) for (int q = 0; q < np; q++) { psum[q] = psum[2 * q] + psum[2 * q + 1]; pcnt[q] = pcnt[2 * q] + pcnt[2 * q + 1]; }
            long bits = 6;
            for (int q = 0; q < np; q++) {
                long sum = psum[q]; int cnt = pcnt[q];
                int k = 0;
                if (cnt > 0) { long mean = sum / cnt; while (k < 14 && (1L << (k + 1)) <= mean) k++; }
                long bk = Long.MAX_VALUE; int kk = k;
                for (int t = Math.max(0, k - 1); t <= Math.min(14, k + 1); t++) { long b = (long) cnt * (t + 1) + (sum >>> t); if (b < bk) { bk = b; kk = t; } }
                params[q] = kk; bits += 4 + bk;
            }
            if (bits < bestBits) { bestBits = bits; c.porder = p; System.arraycopy(params, 0, c.params, 0, np); }
        }
        return bestBits;
    }

    private void subframe(int[] x, int n, int bps, Choice c) {
        if (c.order < 0) { /* verbatim */
            bw.put(0, 1); bw.put(1, 6); bw.put(0, 1);
            for (int i = 0; i < n; i++) bw.putSigned(x[i], bps);
            return;
        }
        bw.put(0, 1); bw.put(8 | c.order, 6); bw.put(0, 1);
        for (int i = 0; i < c.order; i++) bw.putSigned(x[i], bps);
        bw.put(0, 2); bw.put(c.porder, 4);
        int parts = 1 << c.porder, plen = n >> c.porder;
        for (int q = 0; q < parts; q++) {
            int k = c.params[q];
            bw.put(k, 4);
            int s0 = q == 0 ? c.order : q * plen, s1 = (q + 1) * plen;
            for (int i = s0; i < s1; i++) {
                int r = res(x, i, c.order);
                long u = ((r << 1) ^ (r >> 31)) & 0xFFFFFFFFL;
                long hi = u >>> k;
                bw.zeros(hi); bw.put(1, 1);
                if (k > 0) bw.put((int) (u & ((1L << k) - 1)), k);
            }
        }
    }

    private static void utf8(Bits b, long v) {
        if (v < 0x80) { b.put((int) v, 8); return; }
        int n = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : 6;
        b.put((int) ((0xFF00 >> n) & 0xFF) | (int) (v >>> (6 * (n - 1))), 8);
        for (int i = n - 2; i >= 0; i--) b.put(0x80 | (int) ((v >>> (6 * i)) & 0x3F), 8);
    }

    static int crc8(byte[] b, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) { c ^= b[i] & 255; for (int k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 255 : (c << 1) & 255; }
        return c;
    }
    static int crc16(byte[] b, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) { c ^= (b[i] & 255) << 8; for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xFFFF : (c << 1) & 0xFFFF; }
        return c;
    }

    /* écriture bit à bit (poids fort d'abord), par paquets de 32 bits */
    private static final class Bits {
        byte[] buf = new byte[1 << 16];
        int bytes = 0, nacc = 0;
        long acc = 0;
        void reset() { bytes = 0; nacc = 0; acc = 0; }
        /* octets complets écrits (à n'appeler qu'aligné) */
        int len() { return bytes; }
        private void flushAcc() {
            while (nacc >= 8) {
                if (bytes == buf.length) { byte[] n = new byte[buf.length * 2]; System.arraycopy(buf, 0, n, 0, bytes); buf = n; }
                buf[bytes++] = (byte) (acc >>> (nacc - 8));
                nacc -= 8;
            }
            acc &= (1L << nacc) - 1;
        }
        void put(int v, int nb) {
            if (nb == 0) return;
            acc = (acc << nb) | (v & ((1L << nb) - 1));
            nacc += nb;
            if (nacc >= 32) flushAcc();
        }
        void putSigned(int v, int nb) { put(v & (int) ((1L << nb) - 1), nb); }
        void zeros(long n) { while (n > 24) { put(0, 24); n -= 24; } put(0, (int) n); }
        void align() { if ((nacc & 7) != 0) put(0, 8 - (nacc & 7)); flushAcc(); }
    }
}
