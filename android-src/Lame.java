package fr.aflament.sillon;

/* Encodeur MP3 LAME compilé pour le téléphone */
final class Lame {
    static { System.loadLibrary("sillonlame"); }
    static native long init(int inRate, int channels, int outRate, int kbps);
    static native int encode(long h, short[] pcm, int frames, byte[] out);
    static native int flush(long h, byte[] out);
    static native void close(long h);
}
